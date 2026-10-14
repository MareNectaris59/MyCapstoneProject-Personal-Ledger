# HANDOFF —— 跨会话交接

> **这份文档是给"下一个会话"看的**，不是给用户看的说明书。
> 用户文档在 [README.md](README.md) 与 `docs/`。
> 目的：让一个对本项目一无所知的新会话，能在几分钟内接手，并避开
> 已经在这些坑上浪费过的时间。
>
> 最后更新：2026-10（对应提交 `80069c9`，分支 `main`）

---

## 0. 新会话开场请先做这三件事

```powershell
Set-Location 'D:\Capstone Project'
git log --oneline -5
Get-Content README.md -TotalCount 40
```

然后读本文档的 §2（环境陷阱）—— **那一节比代码更能决定你这一轮是顺利还是挣扎。**

---

## 1. 项目是什么、现在在哪

**个人复式记账与消费分析系统**（毕设）。Spring Boot 3.3.5 + Vue 3 + MySQL 8.0。

| 项 | 现状 |
| --- | --- |
| 工作目录 | `D:\Capstone Project` |
| 分支 / HEAD | `main` / `80069c9`（唯一长期分支，实验分支已合并删除） |
| 提交数 | 3 |
| 跟踪文件 | 103 个（约 1.1 MB） |
| 工具链 | JDK 17.0.7、Maven 3.9.3、MySQL 8.0.31、Node v26.11.1 |
| 数据库 | `personal_ledger`，Flyway 已迁移至 **v2** |
| 后端测试 | 69 项验收 + 注销 + 科目管理，**三套全通过** |

**已完成**：设计文档 / 数据库 / 记账内核（分录、平衡校验、反向撤销、三层幂等）/
账号注销 / 科目增删改 / 前端 4 页（Claymorphism 风格）/ 可移植性改造 / 版本控制

**未完成**：消费分析图表、预算模块、AC-07 并发测试、部署（Nginx/HTTPS）

---

## 2. 环境陷阱清单（最重要的一节）

这一节的每一条都是**实际踩过并浪费时间**的，不是理论风险。

### 2.1 所有 `.ps1` 必须保持纯 ASCII —— 已踩 4 次

Windows PowerShell 5.1 按**系统 ANSI 代码页**读 `.ps1`，而文件是 UTF-8。
中文会被打散成 `閺?` 之类，**破坏字符串结尾引号，整个脚本解析失败**。

```powershell
# 写完脚本务必自检
$b = [IO.File]::ReadAllBytes('xxx.ps1')
($b | Where-Object { $_ -gt 127 }).Count   # 必须为 0
```

代价最小的做法：**脚本里一律用英文**，中文说明放 `docs/`。用户可见的中文提示
用 `\uXXXX` 转义或改由前端承担。

已因此失败的脚本：`build.ps1`、`backup-db.ps1`、`setup.ps1`、`test-subject-api.ps1`

### 2.2 `Set-Location` 在单条命令里可能不生效

每个 `pwsh` 调用是**新进程**，工作目录回到工作区根。在同一命令里
`Set-Location 'x'; mvn ...` 有时 `mvn` 仍在旧目录执行。

**后果极其隐蔽**：`mvn -q` 找不到 pom 就失败，但 `-q` 抑制了错误输出，
而 `$LASTEXITCODE` 又恰好来自别的命令，于是**读到假的 "exit=0"**。
我因此连续七八次以为自己构建成功，实际一直在测试陈旧 jar。

**铁律**：
```powershell
# 用 -f 显式指定 pom，不要依赖当前目录
mvn -B -f 'D:\Capstone Project\backend\pom.xml' clean package -DskipTests
```

### 2.3 不要用 `mvn -q` + `$LASTEXITCODE` 判断构建结果

两者组合会隐藏编译错误。**改为检查输出里的 `BUILD SUCCESS`：**

```powershell
$out = & mvn -B -f '...\pom.xml' clean package -DskipTests 2>&1
$out | Select-String 'BUILD|ERROR' | Select-Object -First 8
```

### 2.4 日志可能写不进磁盘 —— 不要依赖它做诊断

`backend/logs/personal-ledger.log` 曾出现**持续 0 字节**，
`log.debug` / `log.warn` 全部丢失，导致我在日志上白等了好几轮。

**可靠的替代手段：把观测值放进异常消息，通过 HTTP 响应读回来。**

```java
throw BizException.of(ResultCode.PARAM_INVALID,
        "PROBE root=[" + rootCode + "] candidates=" + list + " best=[" + best + "]");
```

调用一次接口就能看到全部中间值。定位完记得删除探针。

同理，`if (true) throw ...` 这种**行为探针**可以确认某方法是否被执行
（我用它证明了 `nextCode` 确实被调用，而日志里什么都没有）。

### 2.5 原生命令的 stderr 会让 PowerShell 抛致命错误

`mysql` 的密码警告、`java -version` 的版本横幅都走 stderr。
配合 `$ErrorActionPreference = 'Stop'` 会变成 `NativeCommandError`。

**做法**：`$ErrorActionPreference = 'Continue'`，改用 `$LASTEXITCODE` 判断，
并把调用包进 `cmd.exe /c ".... 2>&1"`。

另外 `mysqldump` 的警告与版本号会**挤在同一行**，匹配前要先用正则剥掉：
```powershell
$text = ($text -replace 'mysql:.*?insecure\.\s*', '').Trim()
```

### 2.6 不要用 PowerShell 的 `>` 重定向生成 UTF-8 文件

PowerShell 5.1 的 `>` 写 **UTF-16 + BOM**，会毁掉 mysqldump 的 UTF-8 输出
（中文全乱码）。`backup-db.ps1` 因此改为走 `cmd.exe /c "... > file"`。

同理，脚本里读写真 UTF-8 要显式指定：
```powershell
[IO.File]::WriteAllText($f, $t, (New-Object Text.UTF8Encoding($false)))  # 无 BOM
```

### 2.7 `edit` 工具处理含 `${...}` 的 `.vue` 模板会失败

Vue 模板里的 `${...}` 不是合法 JS/TS，编辑工具解析会报错。

**做法**：改用 `write` 写整个文件，或用 PowerShell 按行号做手术：
```powershell
$lines = [System.Collections.Generic.List[string]]([IO.File]::ReadAllLines($f, [Text.Encoding]::UTF8))
# ... 拼接 $out ...
[IO.File]::WriteAllLines($f, $out, (New-Object Text.UTF8Encoding($false)))
```

### 2.8 行号手术容易连带删除相邻方法

我用行号范围替换方法时，**误删了紧随其后的 `findTypeRoot`**，
导致 `create()` 编译失败。替换后**务必检查被删范围内的所有方法都还在**：

```powershell
Get-Content $f | Select-String 'private |public ' | ForEach-Object { ... }
```

### 2.9 后台 java 进程的管理

- 启动用**绝对路径**：`& java -jar 'D:\...\xxx.jar'`
  （相对路径会因 §2.2 找不到文件）
- 连续启动两个后台 job 时，后一个的 `Get-Process java | Stop-Process`
  会杀掉前一个。**先杀干净，再启动一个**
- 服务就绪需要约 20–25 秒，用轮询确认而不是固定 `Start-Sleep`：
  ```powershell
  for ($i=0; $i -lt 25; $i++) { Start-Sleep 3
    try { if ((Invoke-WebRequest 'http://127.0.0.1:8080/' -UseBasicParsing -TimeoutSec 4).StatusCode -eq 200) { break } } catch {} }
  ```
- 用户会手动 kill 后台进程（job 状态显示 `killed before exit`）—— 那是正常操作，不是崩溃

### 2.10 其他

- 控制台输出中文会乱码（ANSI/UTF-8 混用），**不要据此判断文件内容**；用 `read` 工具看
- `Get-ChildItem` 的 `-Filter` 在路径不存在时返回空而不报错，会掩盖问题
- 新增 `.ps1` 后要 `git add`，`.gitignore` 未排除脚本

---

## 3. 如何验证（每次改完必做）

```powershell
# 1. 构建（注意 -f 与 BUILD SUCCESS）
mvn -B -f 'D:\Capstone Project\backend\pom.xml' clean package -DskipTests

# 2. 启动
& java '-Dfile.encoding=UTF-8' -jar 'D:\Capstone Project\backend\target\personal-ledger-1.0.0-SNAPSHOT.jar'

# 3. 轮询就绪后跑三套测试
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-api.ps1'              # 69 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-delete-account.ps1'
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-subject-api.ps1'
```

改前端时**必须重新 `npm run build`**（产物输出到
`backend/src/main/resources/static/`），否则 jar 里还是旧界面。

**没有浏览器能力**：视觉正确性只能由用户确认，不要声称"看起来没问题"。

---

## 4. 关键设计决策（偏离常规的地方）

这些是**有意为之**的，改动前先理解原因。

| 决策 | 原因 |
| --- | --- |
| `entry` 是唯一真相，`account_meta.balance` 只是缓存 | 缓存由行锁原子更新，另有 `/balance-check` 对账接口校验，两者必须一致 |
| 撤销用**反向分录法**，原记录永不修改/删除 | append-only，任何时点账目可还原审计（设计文档 §1.3） |
| 账号注销是**软注销** | 账号失效 + 个人信息清除，但**账务流水保留** —— 物理删除会破坏 append-only。返回文案已如实告知用户 |
| 界面不出现「借/贷」「科目」 | 面向普通用户。天平组件用「支出方/收入方」，分类页已删除 |
| Claymorphism **不用技能的粉彩糖果色** | 技能那套定位是 "children's apps"，会削弱财务数据可信度。改用同技能的 Personal Finance Tracker 色板 |
| `PUBLIC_PATHS` 逐条列出，不用 `/auth/**` 通配 | 曾因此让 `/auth/me` 变成公开接口，令牌吊销后触发 NPE（500） |
| 科目编码由 `SubjectService.nextCode` 生成 | 规则见 §5，**踩过三个 bug，不要重写这段而不读注释** |

---

## 5. 科目编码规则（易错，单独强调）

```
根（level 1）   "1" 资产、"5" 费用        1 位，纯展示段
level 2        100101 现金 ～ 100105 储蓄   6 位
               500101 餐饮 ～ 500109 其他
level 3        50010101 早餐              8 位 = 父编码 + 2 位
```

**规则：末 2 位是序号，前面所有位是分组段（prefix）。**

**核心陷阱：根编码 1 位、子科目 6 位，两者长度不成固定关系。**
不要用任何长度公式推导。踩过的三个 bug：

1. `rootCode.length() + 4` = 5 → 所有候选被长度判断排除，序号恒为 0
2. `Integer.parseInt(rootCode + "0100")` = 10100（少一位，正确是 100100）
3. 软删除行占用编码：`uk_user_code (user_id, subject_code)` **不区分 `deleted`**，
   而查询被 `@TableLogic` 自动加 `deleted=0` 看不见这些行 → 撞唯一索引。
   触发路径很日常：**新建账户 → 删除 → 再新建**，第二次必然 500。
   修法：`SubjectMapper.selectAllCodesIncludingDeleted` 用显式 SQL 绕过过滤。

`subject_code` 仅用于展示与排序，层级由 `parent_id` / `path` / `level` 表达。

---

## 6. 下一步候选（按建议优先级）

| | 任务 | 说明 |
| --- | --- | --- |
| **1** | **AC-07 并发测试** | 100 线程各记 1 元，最终余额精确减少 100.00。验证行锁与固定加锁顺序（`applyBalanceDeltas` 按 subjectId 升序）。**内核证据链的最后一块**，工作量小 |
| **2** | 推到远程仓库 | 目前只有本机 + U 盘，毕设数据有丢失风险 |
| **3** | 消费分析图表 | 系统唯一完全缺失的核心功能。需先做 `/api/v1/analysis/**`，再用 ECharts |
| **4** | 预算模块 | 表与实体已就绪，算法口径见设计文档 §7.5 |
| **5** | 部署（Nginx/HTTPS） | 未开始 |

**用户偏好**：倾向"小而美"，反感冗余设计与解释性文案。界面文案只回答
"我该填什么"和"现在发生了什么"，不解释"系统为什么这么设计"。

---

## 7. 约定与红线

- **不要修改 `V1__baseline.sql` / `V2__*.sql`** —— Flyway 校验 checksum，
  改了会拒绝启动。结构变更一律新增 `V3__*.sql`
- `backend/src/main/resources/static/` 是 Vite 输出目录，**会被 `npm run build` 清空**，
  不要放手写文件
- 依赖缓存在项目**外**：`D:\m2repo`、`D:\npm-cache`。**不要移回项目内** ——
  IDEA 曾因扫描 `.m2repo` 里的 `hutool-parent.pom` 报出 21 条
  `Module 'hutool-*' not found`
- 已执行过的迁移脚本、`settings.xml`（已删除）、`.mvn/maven.config`（已删除）
  都是历史包袱，不要恢复
- 提交信息中含中文时，写成文件用 `git commit -F <file>`（PowerShell 不支持 heredoc），
  并确保文件是 **UTF-8 无 BOM**
- `.skills/` 是第三方技能包（692 文件 / 21 MB），**已 gitignore**，
  设计令牌来源已记录在 `docs/前端Brutalism改造说明书.md` §0，不必依赖它

---

## 8. 数据库位置（用户问过，容易答错）

数据库**不在项目目录**，在 MySQL 实例的数据目录：

```
C:\ProgramData\MySQL\MySQL Server 8.0\Data\personal_ledger\     （每表一个 .ibd）
```

**属于整个 MySQL 实例**，同一实例上还有约 19 个其他库（sakila、world、
leadnews_* 等）。`C:\ProgramData` 受系统保护，普通权限读不到。

**不要建议改 `datadir`** —— 会一次性影响全部 20 个库，且 `.ibd` 无法单独取用。
要"数据跟着项目走"就用 `.\backup-db.ps1` 导出 `.sql`。详见
[数据库备份说明](docs/数据库备份说明.md)。
