# 前端工程说明

Vue 3 + Vite 单文件组件工程，为「个人复式记账与消费分析系统」提供界面。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Vue | ^3.5.44 | Composition API + `<script setup>` |
| Vite | ^7.1.7 | 构建与开发服务器 |
| Vue Router | ^4.6.4 | hash 模式（见下） |
| Pinia | ^4.0.3 | 状态管理 |
| Axios | ^1.16.1 | HTTP 客户端 |
| TypeScript | 未使用 | 源码是 JS。曾装过 `typescript` + `vue-tsc` 并配了 `type-check` 脚本，但仓库里没有 `tsconfig.json`，那条命令必然报错，已连同依赖一起移除。理由见文末「为什么没有 TypeScript」 |
| Element Plus | ^2.14.7 | 装了依赖，**尚未接入**（见下） |
| ECharts | ^6.1.0 | 装了依赖，**尚未使用**（消费分析图表待后端接口就绪） |

## 两种工作方式

### A. 开发模式（改代码即时热更新）

需要两个进程同时跑：

```powershell
# 终端 1：后端
Set-Location -LiteralPath 'D:\Capstone Project\backend'
.\build.ps1 run

# 终端 2：前端开发服务器
Set-Location -LiteralPath 'D:\Capstone Project\frontend'
npm run dev
```

打开 **http://127.0.0.1:5173** —— 这是开发地址，改 `.vue` 文件即时生效。

`vite.config.js` 里配了代理：前端的 `/api/**` 请求会转发到 `http://127.0.0.1:8080`，
所以前端代码里的请求路径在开发与生产两种模式下完全一致，无需任何分支判断。

### B. 生产模式（构建后由后端托管）

```powershell
Set-Location -LiteralPath 'D:\Capstone Project\frontend'
npm run build
```

产物直接输出到 `backend/src/main/resources/static/`（**无需手工拷贝**），
然后重启后端，打开 **http://127.0.0.1:8080** 即可。

> 注意：`npm run build` 会**清空** `backend/src/main/resources/static/`，这是 Vite 的
> `emptyOutDir` 行为。正因为如此，那里不能再放任何手写文件。

## 目录结构

```
src/
├── main.js                    应用入口：挂载 Pinia 与路由
├── App.vue                    外壳：侧栏 + 主区，或裸路由（登录页）
├── api/
│   └── index.js               Axios 实例 + 接口封装 + 拦截器
├── stores/
│   └── auth.js                Pinia store：会话、科目树、账户、提示
├── router/
│   └── index.js               路由表 + 权限守卫
├── utils/
│   └── format.js              金额/日期格式化、科目树压平、标签映射
├── assets/
│   └── theme.css             ★ 设计令牌 + 全部组件样式（Claymorphism 轻量灰蓝）
├── components/
│   ├── AppToast.vue          通知条
│   └── BalanceScale.vue      ★ 天平式借贷分录预览
└── views/
    ├── LoginView.vue         登录 / 注册
    ├── DashboardView.vue     总览：账户 + 期初建账 + 最近记录
    ├── RecordView.vue        记一笔：支出 / 收入 / 转账，可就地新建分类
    ├── LedgerView.vue        账目：组合筛选 + 排序 + 分录详情 + 冲正
    ├── CategoryView.vue      支出分类：新建 / 改名 / 停用 / 删除
    ├── BudgetView.vue        预算：额度、结转、执行率、预警
    ├── AnalysisView.vue      消费分析：ECharts 四图 + CSV 导出
    ├── SubjectDetailView.vue 科目明细：余额 + 逐笔变动
    └── SettingsView.vue      账号设置：改昵称 / 改密码 / 注销账号
```

## 三个必须先理解的设计约定

### 1. 业务错误是 HTTP 200 + 非 0 code

这是本项目最容易踩的坑。后端的约定（见 `docs/系统设计说明书.md` §6.1.4）：

> 业务错误统一返回 **HTTP 200 + 非 0 的 `code`**；
> 仅认证失败返回 401、无权限 403、系统异常 500。

所以**不能靠 `catch` 判断业务失败**。`src/api/index.js` 的响应拦截器把它统一
转成 reject，调用方仍可 `try/catch`，但错误对象带上了 `code` 与 `traceId`。

### 2. 金额是字符串，不是数字

后端所有金额字段都是字符串（`"30.00"`），目的是避免 JSON 浮点精度丢失。

- 展示：走 `utils/format.js` 的 `formatMoney()`
- **回传：一律用原始字符串，绝不 `Number()` 转换后再发**

`BalanceScale.vue` 里为了判断"是否平衡"用了 `parseFloat` 求和，但那只用于**判断**，
展示仍走 `formatMoney`，不回传。

### 3. 路由用 hash 模式

后端只托管静态资源，没有为 SPA 配置「找不到路径就返回 index.html」的回退规则。
history 模式下用户刷新 `/ledgers` 会直接 404。用 hash 模式（`#/ledgers`）则完全
不需要服务端配合，部署到 Nginx 时也不用写 `try_files`。

## 为什么用 hash 模式而不是 history 模式

同上。如果将来要改成 history 模式，需要同时：

1. `router/index.js` 把 `createWebHashHistory` 换成 `createWebHistory`
2. 后端加一个 fallback（把非 `/api/**` 的 GET 请求转发到 `index.html`）
3. 或 Nginx 加 `try_files $uri $uri/ /index.html;`

## Element Plus 与 ECharts 的状态
## Element Plus 与 ECharts 的状态

**Element Plus 已安装但源码里没用**，这是有意的：当前所有组件手写 CSS，
样式集中在 `assets/theme.css` 一处，没必要为了几个控件引入一整套设计系统。
若将来引入，应**覆盖其 CSS 变量**而不是逐个改组件。

**ECharts 已在使用**：`views/AnalysisView.vue` 画了四个图（占比饼图、趋势折线、
贡献度双向条形、同环比柱状）。按需引入（`echarts/core` + 3 种图表 + 4 个组件），
并单独拆到 `echarts-*.js` 分包，只在 `/analysis` 路由懒加载时下载，
不影响其它页面的首屏体积。

## 设计令牌来源

`assets/theme.css` 的配色、圆角、阴影、字体来源与取舍，见
[前端改版方案](../docs/前端改版方案.md) §2（含"两处刻意偏离技能规范"的理由）：
技能默认配色是粉彩糖果色，定位偏"儿童应用"，与财务数据的可信感冲突，
因此改用了同技能的 Personal Finance Tracker 色板并做了对比度修正。

字体：**Fira Code（数字/金额/编码）+ Fira Sans（界面文字）**。
Fira 不含中文字形，字体栈必须回退到 `Microsoft YaHei` 等中文字体，
否则中文会掉到浏览器默认字体、破坏数字对齐。

## 已知未完成项

- [ ] 源码迁移到 TypeScript（见下方说明：这是独立工程，不是"改后缀"）

---

## 为什么没有 TypeScript

这个项目**不做** TS 迁移，且已经删掉了那套跑不通的工具链。三个理由：

1. **它本来就不工作。** `package.json` 里有 `"type-check": "vue-tsc --noEmit"`，
   但仓库里从来没有 `tsconfig.json`。跑一下就报错退出 ——
   留一个必然失败的命令，比没有这个命令更糟（会让人以为项目有类型检查）。
2. **`checkJs` 之类的折中方案在这里没有价值。** 前端 3227 行（12 个 `.vue` + 5 个 `.js`），
   接口返回全是 `any`。没有类型化的接口契约时，`checkJs: true` 只能查出
   "隐式 any"，查不出真正的错误 —— 付出配置成本却换不到保护。
3. **真做就得整体重写。** 要有意义，得同时定义 API 响应的 interface、
   给 Pinia store 加泛型、把 `ref()` 逐个标注。这是独立工程，
   而当前行为已经被 297 项端到端断言兜住，风险收益不划算。

**如果将来要做**，正确顺序是：先写 `src/api/types.ts` 定义响应结构，
再逐文件从 `stores/` 和 `api/` 开始改（它们是所有视图的数据来源），
最后才动视图。同时把 `typescript`、`vue-tsc` 加回 devDependencies 并补 `tsconfig.json`。
- [ ] 账目导出（目前只有分析结果可导出 CSV）

已完成（曾列在本节）：消费分析图表、预算页面、修改密码与个人资料页面。
