#!/usr/bin/env python3
"""
把 MySQL 的数据导成一个单文件 SQLite 快照（db/personal_ledger.db）。

用途：毕设交材料/答辩演示。老师不需要装 MySQL 就能打开看数据。

三个边界必须说清楚：

1. **只读**。全程只对 MySQL 执行 SELECT，不写、不改、不加锁。
   同时用 --single-transaction 语义（这里靠逐表 SELECT 的一致性，
   对快照用途足够；真要严格一致应在外层包一个 REPEATABLE READ 事务）。

2. **单向**。本脚本把 MySQL 转成 SQLite，不反向。运行中的应用仍然连 MySQL，
   这个 .db 文件是**只读副本**，改了它不会影响系统，也不该改。

3. **金额存成 TEXT**。MySQL 的 DECIMAL 在 SQLite 里没有对应类型：
   存 REAL 会丢精度（0.1+0.2），存 INTEGER 要改单位（分）。
   为了"看到的和系统里的一模一样"，DECIMAL 一律按原样存字符串（'30.00'）。
   代价是 SUM() 前要 CAST：`SELECT SUM(CAST(amount AS REAL)) FROM ledger`。
   这个取舍写在 db/tools/README 与 README.md 里。

字段类型映射：
    tinyint/int/bigint/...  -> INTEGER
    decimal/numeric         -> TEXT   （见上）
    date/datetime/timestamp -> TEXT   （MySQL 返回的 ISO 字符串原样保存）
    varchar/char/text/json  -> TEXT
"""

import argparse
import os
import sqlite3
import subprocess
import sys
from datetime import datetime

# ---------------------------------------------------------------- 常量

# MySQL 的 --batch 模式会把这些字符转义成反斜杠序列，
# 拆分字段后必须还原，否则数据里会混进字面量 \n、\t。
ESCAPE_MAP = {
    "0": "\0",
    "b": "\b",
    "n": "\n",
    "r": "\r",
    "t": "\t",
    "Z": "\x1a",
    "\\": "\\",
    "'": "'",
    '"': '"',
}

INTEGER_TYPES = {
    "tinyint", "smallint", "mediumint", "int", "integer",
    "bigint", "bit", "year",
}


def unescape_field(raw: str):
    """还原 mysql --batch 的转义；\\N 表示 NULL。"""
    if raw == r"\N":
        return None
    if "\\" not in raw:
        return raw
    out = []
    i = 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            out.append(ESCAPE_MAP.get(nxt, nxt))
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def map_type(data_type: str) -> str:
    """MySQL 类型 -> SQLite 类型。见文件头的映射表。"""
    dt = (data_type or "").lower()
    if dt in INTEGER_TYPES:
        return "INTEGER"
    # decimal 故意落到 TEXT：见文件头第 3 条
    return "TEXT"


class MySql:
    """通过 mysql 命令行客户端读数据（不依赖任何 Python MySQL 驱动）。"""

    def __init__(self, exe: str, user: str, password: str, database: str):
        self.exe = exe
        self.user = user
        self.password = password
        self.database = database

    def rows(self, sql: str):
        args = [
            self.exe,
            "-u" + self.user,
            "-p" + self.password,
            "--batch",                    # 制表符分隔、转义特殊字符
            "--default-character-set=utf8mb4",
            "-N",                         # 不要列名表头
            self.database,
            "-e", sql,
        ]
        proc = subprocess.run(args, capture_output=True)
        if proc.returncode != 0:
            raise RuntimeError(
                "mysql 执行失败: " + proc.stderr.decode("utf-8", "replace")
            )
        text = proc.stdout.decode("utf-8")
        # Windows 上 mysql 客户端输出 CRLF。不归一化的话，每行最后一个字段会残留
        # 一个 \r，把列名污染成 "id\r"、"NO\r"，生成的 DDL 直接语法错误。
        # 只替换真正的行分隔符 CRLF；字段内部的 CR 在 --batch 下已被转义成
        # 反斜杠加 r 两个字符，不会被这里误伤。
        text = text.replace("\r\n", "\n")
        # 末尾换行会多出一个空串，先削掉再判断
        text = text.rstrip("\n")
        if not text:
            return []
        return [line.split("\t") for line in text.split("\n")]

    def tables(self):
        raw = self.rows(
            "SELECT table_name FROM information_schema.tables "
            f"WHERE table_schema='{self.database}' AND table_type='BASE TABLE' "
            "ORDER BY table_name"
        )
        return [r[0] for r in raw]

    def columns(self, table: str):
        """返回 [(name, data_type, nullable), ...]，保持定义顺序。"""
        raw = self.rows(
            "SELECT column_name, data_type, is_nullable "
            "FROM information_schema.columns "
            f"WHERE table_schema='{self.database}' AND table_name='{table}' "
            "ORDER BY ordinal_position"
        )
        return [(r[0], r[1], r[2]) for r in raw]

    def primary_key(self, table: str):
        raw = self.rows(
            "SELECT column_name FROM information_schema.statistics "
            f"WHERE table_schema='{self.database}' AND table_name='{table}' "
            "AND index_name='PRIMARY' ORDER BY seq_in_index"
        )
        return [r[0] for r in raw]

    def count(self, table: str) -> int:
        raw = self.rows(f"SELECT COUNT(*) FROM `{table}`")
        return int(raw[0][0]) if raw else 0


def build_create_sql(table: str, columns, pk) -> str:
    """
    生成 SQLite 的 CREATE TABLE。

    单列整数主键写成 `INTEGER PRIMARY KEY` —— 这是 SQLite 里唯一能让该列
    成为 rowid 别名的写法，写入显式 id 时不会额外建索引，查询也快。
    其余主键（含复合）用表级 PRIMARY KEY 约束。
    """
    lines = []
    single_int_pk = (
        len(pk) == 1
        and any(c[0] == pk[0] and map_type(c[1]) == "INTEGER" for c in columns)
    )

    for name, data_type, nullable in columns:
        col_type = map_type(data_type)
        if single_int_pk and name == pk[0]:
            lines.append(f'  "{name}" INTEGER PRIMARY KEY')
            continue
        piece = f'  "{name}" {col_type}'
        if nullable == "NO":
            piece += " NOT NULL"
        lines.append(piece)

    if pk and not single_int_pk:
        cols = ", ".join(f'"{c}"' for c in pk)
        lines.append(f"  PRIMARY KEY ({cols})")

    body = ",\n".join(lines)
    return f'CREATE TABLE "{table}" (\n{body}\n)'


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export the MySQL database into a single SQLite snapshot file"
    )
    parser.add_argument("--mysql", required=True, help="mysql.exe 的完整路径")
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="")
    parser.add_argument("--database", default="personal_ledger")
    parser.add_argument("--out", required=True, help="输出的 .db 文件路径")
    args = parser.parse_args()

    if not os.path.isfile(args.mysql):
        print(f"找不到 mysql 客户端: {args.mysql}", file=sys.stderr)
        return 2

    mysql = MySql(args.mysql, args.user, args.password, args.database)

    # 覆盖前先删：SQLite 打开已存在的文件会沿用旧内容，
    # 留下上次快照里已删除的表，看起来像"数据凭空多了几张表"。
    if os.path.exists(args.out):
        os.remove(args.out)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    conn = sqlite3.connect(args.out)
    conn.execute("PRAGMA journal_mode=DELETE")   # 快照不需要 WAL 的 -wal/-shm 附属文件

    tables = mysql.tables()
    if not tables:
        print("MySQL 里没有任何表，无法生成快照", file=sys.stderr)
        return 3

    print(f"源库 {args.database} 共 {len(tables)} 张表")

    stats = []
    for table in tables:
        columns = mysql.columns(table)
        pk = mysql.primary_key(table)
        create_sql = build_create_sql(table, columns, pk)
        conn.execute(create_sql)

        source_count = mysql.count(table)
        if source_count == 0:
            stats.append((table, 0, 0))
            print(f"  {table:<24} 0 行（空表）")
            continue

        # 两套标识符写法，不能混用：
        #   SQLite 的 INSERT 用双引号没问题；
        #   MySQL 的 SELECT 必须用反引号 —— MySQL 里双引号是字符串字面量，
        #   写 SELECT "id" 会每行返回字符串 "id" 而不是 id 列的值。
        #   （第一版就是踩了这个：导出来的每行都是列名。）
        insert_names = ", ".join(f'"{c[0]}"' for c in columns)
        select_names = ", ".join(f"`{c[0]}`" for c in columns)
        placeholders = ", ".join("?" for _ in columns)
        insert_sql = f'INSERT INTO "{table}" ({insert_names}) VALUES ({placeholders})'

        # 一次性读整表再批量写：本库最大表约 1.2 万行，内存完全够。
        # 分块只是为了不构造一个巨大的 executemany 列表。
        raw_rows = mysql.rows(f"SELECT {select_names} FROM `{table}`")
        batch = []
        inserted = 0
        for raw in raw_rows:
            if len(raw) != len(columns):
                # 字段数与列数不符通常意味着数据里有未转义的制表符，
                # 静默跳过会造成"看起来成功但少了几行"，所以直接报错。
                raise RuntimeError(
                    f"{table} 有一行字段数 {len(raw)} != 列数 {len(columns)}；"
                    "数据里可能含未转义的制表符或换行"
                )
            batch.append([unescape_field(f) for f in raw])
            if len(batch) >= 500:
                conn.executemany(insert_sql, batch)
                inserted += len(batch)
                batch.clear()
        if batch:
            conn.executemany(insert_sql, batch)
            inserted += len(batch)

        stats.append((table, source_count, inserted))
        flag = "OK" if source_count == inserted else "MISMATCH"
        print(f"  {table:<24} {inserted} 行  [{flag}]")

    # ---- 元信息表：让拿到文件的人知道它是什么、什么时候导的
    conn.execute(
        "CREATE TABLE _snapshot_meta ("
        '"key" TEXT PRIMARY KEY, "value" TEXT)'
    )
    meta = {
        "source_engine": "MySQL",
        "source_database": args.database,
        "exported_at": datetime.now().isoformat(timespec="seconds"),
        "note": "只读快照，仅用于查看与交付；运行中的系统仍使用 MySQL。",
        "money_columns": "DECIMAL 以 TEXT 保存原始字符串，计算前请 CAST(... AS REAL)",
    }
    conn.executemany(
        "INSERT INTO _snapshot_meta VALUES (?, ?)", list(meta.items())
    )
    conn.commit()
    conn.execute("VACUUM")   # 压缩文件，去掉删除页与碎片
    conn.close()

    bad = [s for s in stats if s[1] != s[2]]
    size_kb = round(os.path.getsize(args.out) / 1024, 1)
    print()
    print(f"输出: {args.out}")
    print(f"大小: {size_kb} KB")
    if bad:
        print(f"行数不一致的表: {bad}", file=sys.stderr)
        return 4
    print("所有表行数与源库一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
