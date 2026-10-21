# HANDOFF —— 跨会话交接

> **这份文档是给"下一个会话"看的**，不是给用户看的说明书。
> 用户文档在 [README.md](README.md) 与 `docs/`。
> 目的：让一个对本项目一无所知的新会话，能在几分钟内接手，并避开
> 已经在这些坑上浪费过的时间。
>
> 最后更新：2026-10（对应提交 `7f8153d`，分支 `main`）

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
| 分支 / HEAD | `main` / `2c8cc5e`（唯一长期分支，Aero 实验分支已合并删除） |
| 提交数 | 28 |
| 跟踪文件 | 149 个（约 1.7 MB） |
| 工具链 | JDK 17.0.7、Maven 3.9.3、MySQL 8.0.31、Node v26.11.1 |
| 数据库 | `personal_ledger`，Flyway 已迁移至 **v5** |
| 后端测试 | **单元测试 115 项 + 端到端七套 381 项全通过**，AC-01~13 全覆盖 |
| 测试文档 | [单元测试说明](docs/单元测试说明.md)：115 项清单 + 真实发现的缺陷 |
| 功能文档 | [功能清单](docs/功能清单.md)：49 个接口 + 10 个页面的完整清单 |

**已完成**：设计文档 / 数据库 / 记账内核（分录、平衡校验、反向撤销、三层幂等、并发安全）/
账号注销 / 科目增删改 / 改密码 / 改昵称 / 账目组合查询与排序 / 科目明细页 /
预算模块（结转与预警）/ 消费分析模块（ECharts + CSV 导出）/
前端 8 页（Claymorphism 风格）/ 可移植性改造 / 版本控制

**未完成（按计划跳过）**：邮箱验证码与 Redis（FR-U-01/05/06）、头像与默认账本（FR-U-03 的一部分，
本系统没有展示位与多账本概念）、数据导入、部署（Nginx/HTTPS）

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

### 2.1b `Get-Content` 不带 `-Encoding UTF8` 会**数错行数**

同一个坑的另一个变种，但后果不是报错而是**悄悄给出错误数字**。

```powershell
(Get-Content 'README.md').Count                          # 254  ← 错
(Get-Content 'README.md' -Encoding UTF8).Count           # 359  ← 对
```

实测偏差很大（README 差 105 行、HANDOFF 差 96 行、数据库设计差 183 行），
原因同 §2.1：PS 5.1 按 ANSI 代码页解码 UTF-8，多字节序列被拆散。

**危险之处**：它不报错。若用它核对"文档写完了吗 / 行数对不对"，
会得到一个小得多的数字而毫无察觉 —— 本人就据此在总结里写过错误的行数。

**规则**：凡是用 `Get-Content` 做统计或判断，一律加 `-Encoding UTF8`；
或者改用 `[IO.File]::ReadAllLines($path, [Text.Encoding]::UTF8).Count`。

### 2.1c PowerShell 5.1 与 PowerShell 7 对同一份 JSON 的理解不同

本地开发一直用 **Windows PowerShell 5.1**，而 CI（ubuntu + pwsh）用 **PowerShell 7**。
差异在于 JSON 数字的解析：

```powershell
'{"diff":0.00}' | ConvertFrom-Json | % { $_.diff.GetType().Name }
#  5.1 -> Decimal   "$($_.diff)"  =>  "0.00"   保留标度
#  7.x -> Double    "$($_.diff)"  =>  "0"      标度丢失
```

直接后果：所有将金额与 `'0.00'` 做字符串比较的断言，
**在本地永远是绿的，到 CI 就失败**。这个差异帮我们抓出了两处真实缺陷：
接口把金额序列化成了 JSON 数字，违反“金额一律字符串”的约定。

现已修（Jackson 全局序列化 BigDecimal 为字符串），并在 `Check` 里加了日期容错。
**但这条经验要记住：只在 5.1 上跑测试，等于给类型破口发豁免证。**

### 2.1d git 不读 Windows 系统代理

系统代理开着（比如 `127.0.0.1:7897`）时，浏览器与 PowerShell 都能访问 GitHub，
但 `git push` 会报 `Failed to connect to github.com port 443` —— git 直连，不读 WinINET 代理。

```powershell
git config --local http.proxy  http://127.0.0.1:7897
git config --local https.proxy http://127.0.0.1:7897
```

用 `--local` 而不是 `--global`：代理关了以后，全局设置会让所有仓库都连不上。

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

### 2.10 ⛔ 绝对不要杀 node 进程 —— DSH 自己就是 Node.js

**这是本项目最严重的一条操作禁忌，连续导致 4 次工具调用中途被掐断。**

```powershell
# 绝对不要执行以下任何一条
Stop-Process -Name node -Force
Get-Process node | Stop-Process
taskkill /F /IM node.exe
```

症状：命令执行到一半被中断，报
`The tool call was interrupted after it was recorded, but no result was durably recorded`，
或 `Windows Job runner exited with exit code 4294967295 before proving its managed range empty`。

原因：DSH（这个 harness）本身运行在 Node.js 上。杀掉 node 等于杀掉正在执行你命令的进程，
子进程树无法收敛，job runner 只能报错退出。重启电脑后 `Get-Process node` 仍有 2 个进程，
正是 DSH 自身 —— 这就是最直接的证据。

**这条为什么曾被写进文档**：早期 `npm run dev` 留下孤儿 vite 进程，当时的结论是
"清理 node 进程"。那个结论在 DSH 环境下是**危险的**，已作废。
若确实需要清理 vite 残留，只按 PID 精确处理，并先确认该 PID 不是 DSH：
```powershell
Get-Process node | Select-Object Id, StartTime, Path
# 只有当 Path 指向 frontend/node_modules/vite 时才是 vite，其余一律不要动
```

### 2.11 前端构建：esbuild 会删不掉临时文件（已解决）

症状：
```
[vite:esbuild-transpile] remove C:\Users\...\Temp\esbuild-<hash>: Access is denied.
```

成因：`vite:esbuild-transpile` 把语法降级交给 esbuild 的 Go 子进程；产物较大时
（引入 ECharts 后分包到 MB 级）esbuild 改用"临时文件"而非 stdio 传递数据，
写完再删除该文件，在本机被安全软件锁住 → 整个构建失败。

**解决（已写入 `frontend/vite.config.js`，必须两条同时设）**：
```js
build: {
  minify: 'terser',   // 压缩交给 terser，不经过 esbuild
  target: 'esnext',   // 跳过语法降级，不注册 vite:esbuild-transpile
}
```
只设其中一条无效 —— 实测 `minify: 'terser'` 单独设仍失败，`target: 'esnext'` 单独设也失败。
`terser` 已加入 devDependencies。

`--minify=false` 也能绕过，但产物大 3 倍，不建议作为默认值。

### 2.12 其他

- 控制台输出中文会乱码（ANSI/UTF-8 混用），**不要据此判断文件内容**；用 `read` 工具看
- `Get-ChildItem` 的 `-Filter` 在路径不存在时返回空而不报错，会掩盖问题
- 新增 `.ps1` 后要 `git add`，`.gitignore` 未排除脚本
- `npm exec` / `npm --prefix` **不会**改变工作目录，Vite 会因找不到 `index.html` 而失败；
  `npm run <script>` 会切到包目录，或用 `node node_modules/vite/bin/vite.js build <root>`
  把根目录作为位置参数传入

---

## 3. 如何验证（每次改完必做）

```powershell
# 1. 构建（注意 -f 与 BUILD SUCCESS）
mvn -B -f 'D:\Capstone Project\backend\pom.xml' clean package -DskipTests

# 2. 启动
& java '-Dfile.encoding=UTF-8' -jar 'D:\Capstone Project\backend\target\personal-ledger-1.0.0-SNAPSHOT.jar'

# 3. 轮询就绪后跑六套测试（合计 322 项，全绿才算通过）
mvn -B -f 'D:\Capstone Project\backend\pom.xml' test   # 单元测试 85 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-api.ps1'              # 69 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-acceptance.ps1'       # 43 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-budget.ps1'           # 54 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-analysis.ps1'         # 74 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-profile-query.ps1'    # 57 项
powershell -ExecutionPolicy Bypass -File 'D:\Capstone Project\backend\test-import-export.ps1'    # 25 项
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
| 界面不出现「借/贷」「科目」 | 面向普通用户。分录方向**按业务类型**翻译（支出→支出分类/付款账户，收入→收进账户/收入来源，转账→转入/转出），见 `entryRoleLabel`。分类页曾删除、后以「支出分类」形式加回（只管增删改停用，不含会计概念） |
| 分录文案**必须按业务类型判断**，不能只按借/贷方向 | 踩过：曾把借方统一叫「支出方」、贷方统一叫「收入方」，于是"现金付款"被显示成「收入方」，用户以为收到了钱。借/贷是方向，"支出/收入"是语义，两者不一一对应 |
| Claymorphism **不用技能的粉彩糖果色** | 技能那套定位是 "children's apps"，会削弱财务数据可信度。改用同技能的 Personal Finance Tracker 色板 |
| `PUBLIC_PATHS` 逐条列出，不用 `/auth/**` 通配 | 曾因此让 `/auth/me` 变成公开接口，令牌吊销后触发 NPE（500） |
| 科目编码由 `SubjectService.nextCode` 生成 | 规则见 §5，**踩过三个 bug，不要重写这段而不读注释** |
| 分析模块的 `level` 数的是"用户眼里的分类层级"，不是树深度 | 科目树里「费用」是类型容器，直接用树深度会让 `level=1` 只返回一个 100% 的扇区。实现上 `treeLevel = level + 1` |
| 同环比在基期为 0 时返回 `rate=null` + `rateAvailable=false` | 0 到 100 的增长率不是 100%。宁可显示「—」+ 说明，也不给一个看起来正常的假数字 |
| 贡献度返回前自检 `Σ diff_i = totalDiff` | 不成立就记 error 并把贡献率置 null。宁可少给一列，也不画出错的图 |
| CSV 导出**必须带 UTF-8 BOM** | 不带 BOM 的 UTF-8 CSV 被 Excel 按 GBK 解释，中文列全乱码 |
| `signed_amount` 是借方为正 | 资产科目 +100 是钱进来，**费用科目 +100 是花出去**。同一符号在不同类型科目里含义相反，写措辞时必须先看 `subjectTypeCode` |

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

**设计文档里的功能需求（FR-*）已全部落地**，AC-01~13 十三条都有断言。
剩下的是"按计划跳过"的项，不是遗漏：

| | 任务 | 说明 |
| --- | --- | --- |
| **1** | 推到远程仓库 | 目前只有本机 + U 盘，毕设数据有丢失风险。**用户暂未提供仓库地址**，是唯一真正的待办 |
| **2** | 邮箱验证码激活（FR-U-01/05/06） | 需 SMTP + Redis。`POST /auth/code` 现为占位，直接返回"邮件服务尚未接入" |
| **3** | 数据导入 | 与导出对称，未做 |
| **4** | 部署（Nginx/HTTPS） | 未开始 |

**明确不做的**（有理由，不是没时间）：
- 修改头像：界面没有任何头像展示位，存了也看不到
- 默认账本：系统是单账本设计（一个用户一棵科目树），没有可选项

**用户偏好**：倾向"小而美"，反感冗余设计与解释性文案。界面文案只回答
"我该填什么"和"现在发生了什么"，不解释"系统为什么这么设计"。

---

## 7. 约定与红线

- **不要修改已执行的迁移脚本**（`V1`/`V2`/`V3`）—— Flyway 校验 checksum，
  改了会拒绝启动。结构变更一律新增 `V4__*.sql`
- `backend/src/main/resources/static/` 是 Vite 输出目录，**会被 `npm run build` 清空**，
  不要放手写文件
- 依赖缓存在项目**外**：`D:\m2repo`、`D:\npm-cache`。**不要移回项目内** ——
  IDEA 曾因扫描 `.m2repo` 里的 `hutool-parent.pom` 报出 21 条
  `Module 'hutool-*' not found`
- `backend/settings.xml` 与 `.mvn/maven.config` 已删除，**不要恢复** ——
  它们会把依赖缓存指回项目内，重新引出那 21 条 `hutool-*` 报错
- 提交信息中含中文时，写成文件用 `git commit -F <file>`（PowerShell 不支持 heredoc），
  并确保文件是 **UTF-8 无 BOM**
- `.skills/` 是第三方技能包（692 文件 / 21 MB），**已 gitignore**，不必依赖它。
  原先的 `docs/前端Brutalism改造说明书.md` 已删除 —— 那套风格（brutalism）被推翻

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
