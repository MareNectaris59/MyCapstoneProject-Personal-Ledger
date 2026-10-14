# 个人复式记账与消费分析系统

基于 Spring Boot 3 + Vue 3 的个人复式记账系统。账务内核采用**复式记账**：每笔业务至少两条分录，借贷必须平衡，保证账户资金变动的一致性。

## 当前状态

| 部分 | 状态 |
| --- | --- |
| 设计文档 | ✅ [系统设计说明书](docs/系统设计说明书.md) · [数据库设计](docs/数据库设计.md) · [前端Brutalism改造说明书](docs/前端Brutalism改造说明书.md) · [前端改版方案](docs/前端改版方案.md) · [数据库备份说明](docs/数据库备份说明.md) · [环境搭建与部署](docs/环境搭建与部署.md) |
| 数据库 | ✅ 11 张表，Flyway 迁移至 v2（`V1__baseline.sql` + `V2__add_user_deleted_at.sql`） |
| 后端 | ✅ 记账 / 撤销 / 借贷平衡 / 余额一致性 / 幂等 / 账号注销 / 科目增删改，**69 项验收测试全通过** |
| 前端 | ✅ Vite + Vue 3 单文件组件，**轻量 Claymorphism** 风格，4 个页面（总览 / 记一笔 / 账目 / 账号设置） |
| 预算模块 | ⬜ 未实现（表与实体已就绪，算法口径已设计） |
| 消费分析图表 | ⬜ 未实现（依赖后端 `/api/v1/analysis/**` 接口） |
| 部署（Nginx/HTTPS） | ⬜ 未实现 |

> 前端已于 2026-10 由 Brutalism 改为 Claymorphism，并做了一轮精简：
> 删除「分类与账户」独立页（账户管理并入总览、分类管理并入记账表单），
> 导航从 5 项降到 4 项。决策依据见 [前端改版方案](docs/前端改版方案.md)。

## 环境要求

JDK 17、Maven 3.9+、MySQL 8.0（运行中）、Node.js 20+。

## 数据库在哪里

**数据库不在项目目录里**，它在 MySQL 的数据目录：

```
C:\ProgramData\MySQL\MySQL Server 8.0\Data\personal_ledger\      （每表一个 .ibd 文件）
```

该目录属于整个 MySQL 实例（同一实例上还有约 19 个其他库），受系统保护，普通权限读不到。

**数据要跟着项目走时，用导出而不是搬文件：**

```powershell
.\backup-db.ps1                 # 完整导出到 db\dump\（含测试数据）
.\backup-db.ps1 -SchemaOnly     # 只导出结构（交材料/提交用）
```

详见 [数据库备份说明](docs/数据库备份说明.md) —— 里面解释了为什么**不该**改 `datadir`，
以及 `.ibd` 为什么无法单独取用。

## 快速开始

### 1. 一键初始化（推荐）

项目根目录执行：

```powershell
.\setup.ps1
```

会依次检查工具链 → 检查 Java 版本 → 连 MySQL → **创建数据库（若不存在，绝不覆盖已有数据）**
→ 构建 jar → 打印启动方式。

MySQL 密码不是默认值时：

```powershell
.\setup.ps1 -DbPassword "你的密码"
```

### 2. 数据库凭据用环境变量

`application.yml` 全部支持环境变量覆盖，**不用改代码或配置文件**：

| 变量 | 默认值 |
| --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `3306` / `personal_ledger` |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `123456` |
| `APP_JWT_SECRET` | 开发用默认值，**生产必须改**（≥32 字节） |

```powershell
$env:DB_PASSWORD = 'your-password'
```

在 IDEA 里配在 **Run → Edit Configurations → Environment variables** 更安全（不会误提交）。

### 3. 用 IDEA 运行（推荐）

1. **打开 `backend` 目录**（不是项目根目录）—— IDEA 会识别 `pom.xml` 并导入为 Maven 项目
2. 依赖下载慢时可启用阿里云镜像（可选）：
   `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven`
   → **User settings file** 指向 `<项目>\backend\settings.xml.optional`（勾选 Override）
3. 在 Maven 面板执行一次 `Lifecycle` → **`package`**
   （`spring-boot:run` 和直接运行 main 类都依赖 `target/classes` 里已有编译好的类与资源）
4. 运行 `com.example.ledger.LedgerApplication`
5. 打开 **http://127.0.0.1:8080** —— 前后端同源，不需要额外启动前端服务

### 4. 命令行运行

```powershell
Set-Location -LiteralPath '<项目>\backend'
mvn clean package -DskipTests
java -jar target\personal-ledger-1.0.0-SNAPSHOT.jar
```

### 5. 开发前端（需要热更新时）

**运行项目不需要 Node** —— 编译好的前端已提交在 `backend/src/main/resources/static/`
并打进 jar。只有改前端源码时才需要：


```powershell
Set-Location -LiteralPath '<项目>\frontend'
npm install     # 首次，需要联网
npm run dev
```

打开 **http://127.0.0.1:5173**（`/api` 已配代理转发到 8080）。

> ⚠️ 用完请用 **Ctrl+C 正常退出**，不要直接关窗口 —— 否则 Vite 的 node 子进程会变成孤儿进程残留。

### 6. 构建前端并交给后端托管

```powershell
Set-Location -LiteralPath '<项目>\frontend'
npm run build
```

产物直接输出到 `backend/src/main/resources/static/`（**无需手工拷贝**；该目录会被清空，
所以不要在里面放手写文件）。之后重新 `mvn package` 并重启后端，访问 8080 即可。

### 7. 跑验收测试

后端运行起来后：

```powershell
Set-Location -LiteralPath '<项目>\backend'
powershell -ExecutionPolicy Bypass -File .\test-api.ps1              # 69 项验收
powershell -ExecutionPolicy Bypass -File .\test-delete-account.ps1   # 注销账号
powershell -ExecutionPolicy Bypass -File .\test-subject-api.ps1      # 科目增删改 + 编码生成
```

`test-api.ps1` 覆盖设计文档的 AC-01 ～ AC-06 以及非叶子科目、同账户转账、重复撤销等守卫，
**69 项全通过**才算绿色。另两个脚本覆盖新增的账号注销与科目管理接口。

> 新增科目时的编码由 `SubjectService.nextCode` 自动生成，规则与预置模板一致：
> 根编码是展示段（`1`/`5`），子科目为 `根编码 + 分组段 + 2 位序号`（`100106`）。
> 该逻辑曾连续出过三个 bug（长度公式、基数少一位、软删除行占位），
> 排查经过与原因写在 `docs/数据库设计.md` 的科目编码小节，改动前务必先读。

## 拷到 U 盘 / 换电脑

详见 **[环境搭建与部署](docs/环境搭建与部署.md)**。要点：

| 要拷 | 不用拷 |
| --- | --- |
| `backend/src/`、`frontend/src/`、`docs/`、`db/`、`pom.xml`、`package.json`、`*.ps1`、`README.md` | `backend/target/`（52 MB，可重建）、`frontend/node_modules/`（181 MB，**运行不需要**）、`.idea/`、`.skills/` |

**真正需要拷的只有约 1 MB。** 换机器后跑一次 `.\setup.ps1` 即可。

## 目录结构

```
.
├── .gitignore                      只提交结构导出，不提交含凭据的完整导出
├── backup-db.ps1                   ★ 一键导出数据库到 db\dump\
├── db/
│   └── dump/                       .sql 导出（-schema 结尾的可提交）
├── docs/
│   ├── 系统设计说明书.md           需求、用例、架构、接口、算法口径、验收标准
│   ├── 数据库设计.md               表结构、索引、DDL、关键 SQL、预置科目模板
│   ├── 前端Brutalism改造说明书.md   设计令牌来源、组件规范
│   ├── 数据库备份说明.md           dump vs 搬 datadir 的取舍、还原方法
│   └── 网络环境说明.md             离线运行的影响与修复记录（历史）
├── backend/
│   ├── pom.xml                     Spring Boot 3.3.5 父 POM
│   ├── settings.xml                ★ IDEA 与命令行共用的 Maven 配置
│   ├── test-api.ps1                69 项端到端验收测试
│   ├── test-delete-account.ps1     账号注销流程测试
│   ├── test-subject-api.ps1        科目增删改与编码生成测试
│   └── src/main/
│       ├── java/com/example/ledger/
│       │   ├── common/             统一响应体、错误码、异常处理、金额工具、配置
│       │   ├── security/           JWT 签发校验、令牌黑名单、当前用户
│       │   └── module/
│       │       ├── user/           注册、登录、注销账号、偏好
│       │       ├── subject/        会计科目树 + 账户 + 期初建账
│       │       ├── ledger/         ★ 记账内核：分录构造、平衡校验、冲正
│       │       ├── budget/         预算（表与实体已就绪，服务未实现）
│       │       └── reconcile/      对账日志
│       └── resources/
│           ├── application.yml
│           ├── logback-spring.xml
│           ├── db/migration/       Flyway 迁移脚本
│           │   ├── V1__baseline.sql            基线（11 张表，冻结不可改）
│           │   └── V2__add_user_deleted_at.sql 注销时间字段
│           ├── db/schema.sql       手工建表参考脚本
│           └── static/             ★ 前端构建产物（由 Vite 输出，勿手改）
└── frontend/                       Vite + Vue 3 工程，见 frontend/README.md
```

## 三个核心设计点

### 1. 一张 `subject` 表装下账户与消费分类

原方案把「资金账户」和「消费分类」分成两套实体，结果一笔支出「借 餐饮 / 贷 支付宝」的**借方无处安放**，借贷平衡校验形同虚设。

现在两者都是 `subject` 表的行，靠 `subject_type`（ASSET / LIABILITY / EQUITY / INCOME / EXPENSE）区分：

| 业务 | 借方 | 贷方 |
| --- | --- | --- |
| 买午餐 30 元 | 费用·餐饮·午餐 | 资产·支付宝 |
| 发工资 8000 元 | 资产·银行卡 | 收入·工资 |
| 银行卡转支付宝 500 元 | 资产·支付宝 | 资产·银行卡 |
| 期初建账 10000 元 | 资产·银行卡 | 权益·期初余额 |
| 冲正那笔午餐 | 资产·支付宝 | 费用·餐饮·午餐 |

每行借贷金额都相等 —— 这才叫复式记账。

### 2. 余额是「算」出来的，不是「记」出来的

`entry`（分录）表是唯一真相，只插入、不更新、不删除。`account_meta.balance` 只是缓存，靠行锁原子更新，并由对账接口校验。任何余额都能由分录重算验证。

关键冗余字段 `entry.signed_amount`（借为 +、贷为 −）让「科目余额 = `SUM(signed_amount)`」：

```sql
SELECT COALESCE(SUM(signed_amount), 0) FROM entry WHERE subject_id = ?;
```

**冲正因此自动抵消**，所有统计 SQL 都不需要"排除已冲正"的特殊逻辑。

### 3. 纠错只能撤销，不能删除

采用**反向分录法**：新增一张方向相反、金额相等的记录，原记录永不修改。账务保持 append-only，任何时点状态都可还原与审计。

同时提供三层幂等防护：客户端复用同一 `Idempotency-Key` → 服务端查重 → 数据库 `uk_idem` 唯一索引兜底。重复提交返回首次结果，不会重复记账。

### 4. 账号注销是软注销

`POST /api/v1/auth/delete` 需要密码二次确认。注销后账号永久失效、个人信息（邮箱/昵称/头像）被清除、
邮箱可重新注册，但**账务流水保留** —— 因为物理删除会破坏上面第 3 点的 append-only 约束。
返回给用户的文案如实说明了这一点。

## 关于依赖缓存的位置

Maven 本地仓库在 **`D:\m2repo`**，npm 缓存在 **`D:\npm-cache`** —— 都**不在项目目录内**，这是刻意的。

原因：Maven 仓库含约 2900 个 POM。它曾经位于项目根目录下的 `.m2repo/`，IDEA 扫描项目时会去
导入其中每一个聚合 POM，于是 `hutool-parent.pom` 声明的 21 个 `<module>` 全部报
`Module 'hutool-*' not found`。把缓存移出项目树即彻底消除这类噪音。

如果要改用 Maven 默认仓库（`%USERPROFILE%\.m2\repository`），
删掉 `backend/settings.xml` 里的 `<localRepository>` 一行即可，代价是首次构建需重新下载约 750 MB。

## 重要提醒

- 生产环境务必覆盖 `app.jwt.secret` 与数据库口令
- `backend/src/main/resources/static/` 是 Vite 的输出目录，**会被 `npm run build` 清空**，不要在里面放手写文件
- 修改表结构时：先改 `docs/数据库设计.md`，再新增 `db/migration/V3__*.sql`，
  **不要修改已执行过的 V1/V2**（Flyway 会校验 checksum 并拒绝启动）
- `db/dump/` 里**只有文件名以 `-schema` 结尾的**会被提交；完整导出含测试账号密码哈希，已在 `.gitignore` 中排除
- 所有 `.ps1` 脚本**必须保持纯 ASCII** —— Windows PowerShell 5.1 按 ANSI 读取，
  中文会破坏字符串结尾引号导致解析失败（本项目已踩过三次）
