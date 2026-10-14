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
| TypeScript | ^5.9.3 | 装了依赖，但源码目前是 JS；迁移到 TS 时直接改后缀即可 |
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
│   └── brutalism.css          ★ 设计令牌 + 全部组件样式
├── components/
│   ├── AppToast.vue           顶部横幅通知
│   ├── BalanceScale.vue       ★ 天平式借贷分录预览
│   └── SubjectRow.vue         递归的科目树行
└── views/
    ├── LoginView.vue          登录 / 注册
    ├── DashboardView.vue      总览：账户 + 对账自检 + 期初建账
    ├── RecordView.vue         记一笔：支出 / 收入 / 转账
    ├── LedgerView.vue         账目：分页 + 分录详情 + 冲正
    └── SubjectView.vue        会计科目树
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

两者依赖都已安装，但**源码里还没用**，这是有意为之：

- **Element Plus**：Brutalism 需要完全控制边框（3px 实线）、阴影（`4px 4px 0`，无模糊）、
  圆角（必须为 0）。这些恰好是组件库最难覆盖的部分。当前所有组件手写 CSS，
  样式集中在 `assets/brutalism.css` 一处。若后续引入，应**覆盖其 CSS 变量**
  而不是逐个改组件。
- **ECharts**：消费分析图表需要后端 `/api/v1/analysis/**` 接口，
  那组接口在设计文档 §6.2.6 已定义但尚未实现。接口就绪后再做图表。

## 设计令牌来源

`assets/brutalism.css` 的配色、边框、阴影、字体**全部来自 ui-ux-pro-max-skill 的实际检索输出**，
不是凭经验写的。检索命令与技能返回原文见 `docs/前端Brutalism改造说明书.md` §0。

其中两处是刻意的**对比度修正**（技能要求 4.5:1，原始色板不满足）：

| 项 | 技能原值 | 采用值 | 原因 |
| --- | --- | --- | --- |
| 蓝 | `#2196F3` | `#1F6FEB` | 原值配白字仅 3.1:1，不足 AA |
| 红 | `#FF5252` | `#E53935` | 原值配白字仅 3.0:1 |

字体采用技能 `typography.csv` 中 **"Dashboard Data"** 搭配：
**Fira Code（数字/金额/编码）+ Fira Sans（界面文字）**。
Fira 不含中文字形，因此字体栈必须回退到 `Microsoft YaHei` 等中文字体，
否则科目名会掉到浏览器默认字体、破坏等宽数字的对齐。

## 已知未完成项

- [ ] 消费分析图表（等后端 `/api/v1/analysis/**` 接口）
- [ ] 预算页面（后端预算模块未实现）
- [ ] 源码迁移到 TypeScript（依赖已装，`npm run type-check` 已配置）
- [ ] 个人资料 / 修改密码页面
- [ ] 账目导出（CSV/Excel）
