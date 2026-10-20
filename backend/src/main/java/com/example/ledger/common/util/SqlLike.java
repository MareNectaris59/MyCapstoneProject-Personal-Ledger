package com.example.ledger.common.util;

/**
 * LIKE 通配符转义。
 *
 * <p><b>为什么需要它</b>：用户在搜索框里输入 {@code %} 或 {@code _} 时，
 * 直觉上是在搜"百分号"和"下划线"这两个**字符**；但在 SQL 的 {@code LIKE}
 * 里它们是通配符 —— 单独一个 {@code %} 会匹配全部记录。
 * 不转义的话，搜索功能等于给用户一个"输入 % 就返回所有数据"的按钮，
 * 既反直觉，也让筛选结果凭空变多。
 *
 * <p><b>为什么单独抽成一个类</b>：这段逻辑虽然只有三行，但它是"用参数化查询
 * 就绝无注入风险"这个常见认知的一个**例外** —— 参数化只防注入，不防通配符。
 * 抽出来单独写测试，避免以后有人"优化"掉它。
 *
 * <p><b>依赖约定</b>：转义符用反斜杠，依赖 MySQL 默认的 LIKE 转义规则
 * （{@code sql_mode} 不含 {@code NO_BACKSLASH_ESCAPES}）。
 * 本项目没有修改该 mode，因此成立；若将来迁移数据库或改动 mode，
 * 这里需要改成显式 {@code ESCAPE} 子句。
 */
public final class SqlLike {

    /** LIKE 的默认转义符 */
    private static final char ESCAPE_CHAR = '\\';

    private SqlLike() {
    }

    /**
     * 把用户输入转义成"字面量模式"。
     *
     * <p>顺序很重要：**必须先转义反斜杠本身**，否则后面插入的反斜杠会被二次转义，
     * 变成 `\\%`（匹配"反斜杠 + 任意字符"）而不是"字面量百分号"。
     * 这是字符串转义里最常见的顺序错误，所以单独写测试钉住。
     *
     * @param raw 用户输入，可为 null
     * @return 转义后的字符串；null 或空白返回 null，调用方据此跳过该过滤条件
     */
    public static String escape(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(trimmed.length() + 8);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // 1) 反斜杠本身要先变成两个，才能作为"转义符"出现在模式里
            // 2) % 与 _ 是 LIKE 的两个通配符，加反斜杠后变成字面量
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
