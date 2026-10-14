# 前端 Brutalism 改造说明书（简易版）

> 本文档只说明「要做什么」，不含实施步骤的代码细节。
> 配套文档：[系统设计说明书](系统设计说明书.md) · [数据库设计](数据库设计.md)

## 0. 设计依据：ui-ux-pro-max-skill 检索结果

技能已由你下载到 `D:\Capstone Project\.skills\ui-ux-pro-max-skill-main\`，
核心在 `.claude/skills/ui-ux-pro-max/`（Python 3.12 可直接运行）。
本文档的配色、字体、效果规范**全部来自该技能的实际检索输出**，不是凭经验写的。

### 0.1 检索命令与结论

```bash
# 风格定义
python .../scripts/search.py "neubrutalism bold borders thick shadows" --domain style

# 设计系统（variance 9 = Bold/Asymmetric，density 7 = 标准）
python .../scripts/search.py "personal finance double-entry bookkeeping ledger dashboard" \
  --design-system --variance 9 --density 7 -p "PersonalLedger"

# 两处补查（见 0.3）
python .../scripts/search.py "data dense dashboard numeric tabular monospace table" --domain typography
python .../scripts/search.py "keyboard focus visible" --domain ux
```

### 0.2 风格条目（`styles.csv` 命中 `Neubrutalism`）

| 字段 | 技能返回值 |
| --- | --- |
| Keywords | Bold borders, black outlines, primary colors, thick shadows, no gradients, flat colors, 45° shadows, playful |
| Primary Colors | **`#FFEB3B` 黄 / `#FF5252` 红 / `#2196F3` 蓝 / `#000000` 黑边** |
| Effects | `box-shadow: 4px 4px 0 #000`、`border: 3px solid #000`、无渐变、0 圆角、粗体字 |
| Design System Variables | `--border-width: 3px`、`--shadow-offset: 4px`、`--shadow-color: #000`、高饱和配色、粗体无衬线 |
| Complexity | Low |
| Performance | `cost:low`（无性能代价） |
| Accessibility | `risk:low`，但**要求**：对比度 4.5:1、键盘可达、可见焦点、尊重 reduced-motion |
| Implementation Checklist | 硬边框 2–4px ／ 硬偏移阴影 ／ 高饱和色 ／ 粗体字 ／ 无模糊无渐变 ／ 醒目的"丑萌"观感 |

### 0.3 我否掉了技能的两项输出（按 SKILL.md 的容错规则）

设计系统聚合结果里有**两处明显不适合本项目**，我未采用：

| 项 | 技能返回 | 问题 | 我的处理 |
| --- | --- | --- | --- |
| **Typography** | `Caveat` / `Quicksand`（手写体 + 圆体） | Best For 写的是"个人博客、邀请函、创意作品集"。用在**账目数据表**上完全错误 —— 手写体无法承载等宽数字对齐 | 按规则**重查一次**，改用 `--domain typography "data dense dashboard numeric tabular monospace table"` → 命中 **"Dashboard Data"：Fira Code + Fira Sans**，Best For 明确写着"Dashboards, analytics, data visualization, admin panels" |
| **Colors** | Primary `#1E40AF`、Background `#0F172A`（深色导航蓝） | 这是通用 fintech 后台配色，与 `Brutalism` 风格要求的"高对比、可见边框、anti-design"**自相矛盾** | 按 SKILL.md「Do not accept a generic result」改用 **style 条目自带的 Primary Colors**（黄/红/蓝 + 纯黑） |

SKILL.md 的原话是：*"If that retry fails, state that no verified match was found and
label any general guidance as a fallback. Do not persist unverified output."*
上表两项都做了重查且命中，因此都是**经过验证的数据库匹配结果**，不是我的发挥。

> 其余部分（需求、组件清单、坑点、验收方式）与技能无关，是我的工程判断。

---

## 1. 当前前端现状

### 1.1 文件构成

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `backend/src/main/resources/static/app.js` | 约 800 | 全部页面与逻辑，单个 IIFE |
| `backend/src/main/resources/static/app.css` | 约 190 | 手写样式，含 10 个 CSS 变量 |
| `backend/src/main/resources/static/index.html` | 30 | 入口，`<script>` 引入 vendor |
| `static/vendor/*.js` | — | Vue 3.5.35 / Vue Router 4.6.4 / Axios 1.16.1 浏览器构建 |

**无构建步骤**：Vue 用 global 构建（自带模板编译器），模板是 JS 字符串。
这既是约束也是便利 —— 改样式不需要重新编译，刷新浏览器即可。

### 1.2 现有页面

| 路由 | 页面 | 主要元素 |
| --- | --- | --- |
| `#/login` | 登录 / 注册 | 居中卡片、2–3 个输入框、切换链接 |
| `#/` | 总览 | 4 个统计卡、账户表、期初建账表单、对账卡片 |
| `#/record` | 记一笔 | 3 个 Tab、4–6 个字段、**借贷分录预览块** |
| `#/ledgers` | 账目 | 筛选行、7 列表格、分页、分录详情、冲正表单 |
| `#/subjects` | 会计科目 | 二层科目树表格（7 列） |

### 1.3 现设计的问题

1. **视觉语言过于"通用后台"** —— 蓝白灰配色 + 圆角 + 浅边框，与市面上任意
   CRUD 后台无区别，看不出这是**复式记账**系统。
2. **系统最核心的概念没有视觉表达** —— 借贷平衡只是预览块底部一行小字
   「✓ 借贷平衡」，用户感知不到"平衡"这件事本身。
3. **样式靠内联 style 散落在模板里**（如 `style="max-width:640px"`、
   `style="position:fixed;top:16px..."`），改主题要同时改 JS 和 CSS 两处。
4. **状态区分弱** —— 借方/贷方仅靠浅蓝/浅橙小标签，扫一眼表格分辨不出。
5. **CSS 变量只定义了 10 个**，没有间距、字阶、边框宽度、阴影的成套令牌。

---

## 2. 为什么 Brutalism 适合这个系统

Brutalism（野兽派）在 Web 设计中的核心主张：**结构与数据外显，拒绝装饰性柔化**。

复式记账恰好是一个"诚实"的系统 —— 每笔业务必然产生借贷两条分录、金额必然相等、
余额必然可由分录重算。这套系统不该被包装成圆润轻盈的样子，它应该看起来像**账本**：
硬边框、清晰分栏、等宽数字、不掩饰结构。

三个天然的契合点：

| 系统特性 | Brutalism 表达方式 |
| --- | --- |
| **借贷必须平衡** | 天平式对照块 —— 左右等宽、中间硬线，不平衡时整块告警色 |
| **账务不可变、只可冲正** | 强对比的状态色块：已记账 / 已冲正一眼可分，不用柔和过渡 |
| **金额是精确的十进制** | 全站等宽数字 + 千分位，视觉上强调"这是数据不是文案" |

这也顺带解决了现状问题 1 与 2。

---

## 3. 设计令牌（Design Tokens）

全部落在 `app.css` 的 `:root`，**模板里不再写任何内联样式**。

### 3.1 颜色

**直接采用技能 `Neubrutalism` 条目给出的 Primary Colors**，
配合语义令牌命名（对标技能的 `--color-*` 变量体系）：

| 令牌 | 值 | 来源 | 用途 |
| --- | --- | --- | --- |
| `--color-foreground` | `#000000` | 技能：Black outlines | 文字、边框、阴影；全站唯一"黑" |
| `--color-background` | `#FFFFFF` | 技能：flat colors | 页面底色 |
| `--color-card` | `#FFFFFF` | — | 卡片底色 |
| `--color-primary` | `#FFEB3B` | **技能：Yellow** | 主操作按钮、借贷平衡高亮、当前导航项 |
| `--color-on-primary` | `#000000` | 对比度推导 | 主色上的文字 |
| `--color-danger` | `#FF5252` | **技能：Red** | 超支、冲正、错误、已冲正状态 |
| `--color-on-danger` | `#FFFFFF` | 对比度推导 | 危险色上的文字 |
| `--color-secondary` | `#2196F3` | **技能：Blue** | 次级强调、链接、转账、选中态 |
| `--color-on-secondary` | `#FFFFFF` | 对比度推导 | 次级色上的文字 |
| `--color-muted` | `#F2F2F2` | — | 表头、次级区块底 |
| `--color-muted-foreground` | `#5A5A5A` | 对比度推导 | 弱化文字（须 ≥4.5:1） |
| `--color-success` | `#00C853` | 语义补充 | 已记账、校验通过 |

**规则**：不用渐变、不用半透明、不用低饱和灰蓝。新增颜色前必须先证明现有色板不够用。

> **注意**：技能明确要求 `contrast-text-4.5`。上表 `--color-on-*` 就是为满足它而定的：
> 黄底只能配黑字（黄配白对比度仅 1.3:1），红底只能配白字（红配黑约 3.6:1，不足 4.5:1）。
> 蓝色 `#2196F3` 配白字约 3.1:1 **不达标**，因此蓝底上的文字须用 `#000000`
> （约 8.6:1）—— 这是从技能要求反推出来的修正。

### 3.2 边框与阴影

**直接采用技能的 Effects 与 Design System Variables：**

| 令牌 | 值 | 来源 |
| --- | --- | --- |
| `--border-width` | `3px` | 技能变量原值 |
| `--border` | `3px solid #000` | 技能：`border: 3px solid #000` |
| `--border-thin` | `2px solid #000` | 技能 Checklist 允许 2–4px |
| `--shadow-offset` | `4px` | 技能变量原值 |
| `--shadow-color` | `#000000` | 技能变量原值 |
| `--shadow` | `4px 4px 0 #000` | 技能：`box-shadow: 4px 4px 0 #000` |
| `--shadow-sm` | `2px 2px 0 #000` | 按比例缩小 |

`border-radius` **全站为 0**（技能：`sharp corners (0px)`）。

> 我原稿写的是 6px 阴影，技能规范是 4px —— **按技能的 4px 执行**。

### 3.3 字体

**采用补查命中的 "Dashboard Data" 搭配（Fira Code + Fira Sans）**，
这是 `typography.csv` 中 Best For 直接写着 "Dashboards, analytics, data visualization, admin panels" 的条目：

| 用途 | 字体 | 来源 |
| --- | --- | --- |
| 界面文字 | `"Fira Sans", "Microsoft YaHei", "PingFang SC", system-ui, sans-serif` | 技能：Body Font |
| **数字 / 金额 / 科目编码 / 凭证号** | `"Fira Code", "Consolas", monospace` | 技能：Heading Font（Mono） |

技能对该搭配的说明：*"Fira family cohesion. Code for data, Sans for labels."*
—— 正是本项目需要的：**数据用等宽，标签用无衬线**。

**中文字体不能省** —— 界面上科目名与文案全是中文，而 Fira 系列不含中文字形。
若不回退到 `Microsoft YaHei`，中文会掉到浏览器默认字体，破坏数字对齐。

**当网络可用时**用 Google Fonts 引入（离线时自动回退到系统字体）：

```css
@import url('https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600;700&family=Fira+Sans:wght@300;400;500;600;700&display=swap');
```


### 3.4 字阶与间距

字阶：`11 / 12 / 14 / 16 / 20 / 28 / 40`（px）
间距：4 的倍数 —— `4 / 8 / 12 / 16 / 24 / 32 / 48`
正文 `line-height: 1.6`；表格与数字 `1.4`

---

## 4. 组件级改造清单

### 4.1 按钮

- `3px` 黑边 + `4px 4px 0 #000` 硬阴影，0 圆角
- **按下位移**：`hover` 时 `translate(4px, 4px)` 且阴影归零 —— 视觉上"按进去"
- 三种变体（全部按 §3.1 的对比度约束配色）：
  - 主操作：`--color-primary` 黄底 + 黑字
  - 次操作：白底 + 黑字
  - 危险：`--color-danger` 红底 + **白字**
- 禁用态：白底 + 灰虚线边，不做半透明
- **键盘可达**：`:focus-visible` 出 `3px` 黑外框（技能 Accessibility 要求 `visible-focus`）

### 4.2 卡片（`.panel`）

- `3px` 黑边 + `4px 4px 0 #000`
- 标题区与内容区用 `2px` 黑线分隔，不用留白分隔
- 不加圆角、不加模糊阴影、不加背景渐变（技能 Checklist 明列）

### 4.3 表单

- 输入框 `3px` 黑边、白底、0 圆角
- `focus` 时 `box-shadow: 4px 4px 0 var(--color-secondary)`
- 标签用加粗小字，配 `letter-spacing`
- 校验错误：红底白字块紧贴输入框下方，**不用小字红色提示**（技能 `error-clarity`）


### 4.4 表格

- 表头：`--paper-2` 底 + 2px 全边黑线 + 加粗
- 行分隔：2px 黑线（不是 1px 浅灰）
- **数字列右对齐 + 等宽字体**（现状已右对齐，但字体要换）
- 悬停：整行 `--accent` 底色（现状是极浅灰，几乎看不见）

### 4.5 状态标签

改为**实心色块 + 黑边**，不用浅色描边：

| 状态 | 样式 |
| --- | --- |
| 借 | 黑底白字 `借` |
| 贷 | 黄底黑字 `贷` |
| 已记账 | 绿底黑字 |
| 已冲正 | 红底黑字 + 删除线 |
| 冲正单 | 灰底黑字 |

标签本身也是 `2px` 黑边，不用圆角胶囊。

### 4.6 分录预览（本次改造的重点）

现状是一张小表 + 一行绿色小字。改造为**天平式对照块**：

```
┌─────────────────────┬─────────────────────┐
│ 借 DEBIT            │ 贷 CREDIT           │
│ 费用·餐饮·午餐       │ 资产·支付宝          │
│        30.00        │        30.00        │
└─────────────────────┴─────────────────────┘
         借方合计 = 贷方合计  ✓ 平衡
```

- 左右等宽，中间 3px 黑竖线
- 金额 28px 等宽、右对齐
- **平衡时**整块 `--accent` 黄底；**不平衡时**整块红底白字
- 底部一行等宽字体显示两侧合计

这是全站唯一"讲故事"的地方：用户每记一笔都能看见借贷如何配平，
把设计文档 §5.1 的核心机制变成可感知的界面元素。

### 4.7 导航

- 左侧栏改**深色块 + 黄色左边框**（现状是深蓝灰，辨识度低）
- 当前路由项：`--accent` 黄底黑字，不是左侧细线
- 侧栏与主区之间用 3px 黑线分隔

### 4.8 数据卡片（总览页 4 个统计）

- 数字放大到 40px 等宽
- 卡片右下角加一个几何色块装饰（小黄方块，纯装饰，呼应 Brutalism）
- **对账卡片特殊处理**：借贷差额为 `0.00` 时整块绿底黑字，
  非 0 时红底白字并加粗 —— 这是系统最重要的一项自检，值得最醒目的表达

### 4.9 通知（Toast）

现状是右上角浅色提示条。改为**从顶部滑入的整条横幅**：
3px 黑边 + 硬阴影 + 大写加粗，停留 4.5 秒。

---

## 5. 需要注意的坑

### 5.1 内联样式必须清理

模板里有若干 `style="..."`（如 `max-width:640px`、`style="width:100%"`），
会与新 CSS 打架。改造时必须先把它们抽成 class，否则会出现"改了 CSS 没生效"的困惑。

### 5.2 可访问性不能为风格让步

Brutalism 容易做成"刺眼但不可用"。三条底线：

1. 黄底 (`#FFE500`) 上只能配黑字 —— 配白字对比度不足 3:1
2. 红底 (`#FF3B30`) 上只能配白字
3. 所有交互元素保留 `:focus-visible` 外框，键盘用户能看出焦点在哪

### 5.3 表格在窄屏会溢出

账目表有 7 列，手机上必然横向溢出。方案：给表格容器加
`overflow-x: auto` 并保留硬边框，**不做响应式折叠**（折叠会破坏"结构外显"的初衷）。

### 5.4 无构建步骤带来的限制

由于前端是 `<script>` 直载 + 模板字符串（原因见 [README](file:///D:/Capstone%20Project/README.md) 「本机环境限制」）：

- 样式改动只在 `app.css`，改完刷新浏览器即可，**不需要重新编译后端**
- 但模板字符串里的 class 名改动需要重启后端（资源要重新拷贝到 `target/classes`）
- 若之后能联网切到 Vite，可把 `app.js` 拆成单文件组件，样式改造可复用

### 5.5 等宽字体的中文回退

`JetBrains Mono` 等字体不含中文字形。字体栈必须回退到中文字体，
否则科目名会掉到浏览器默认字体，破坏对齐。这也是字体栈里
`"Microsoft YaHei"` 必须出现的原因。

---

## 6. 工作量与改动范围

| 文件 | 改动 |
| --- | --- |
| `app.css` | **几乎重写**，约 190 行 → 约 380 行 |
| `index.html` | 微调：加字体预加载、可能加一个顶部横幅容器 |
| `app.js` | 中等：清理内联样式、分录预览组件改写、状态标签 class 改名 |
| 后端 | **零改动**（纯样式改造，不碰接口与账务逻辑） |

改动集中在 2 个文件，且**不触达任何账务代码** —— 改造风险仅限视觉层，
不影响已验证通过的 69 项后端测试。

---

## 7. 验收方式

改造后逐项确认：

- [ ] 全站 `border-radius` 为 0（可全局搜索 `radius` 确认）
- [ ] 无 `linear-gradient`、无 `box-shadow` 模糊值（阴影偏移必须为实心）
- [ ] 金额列全部等宽字体且千分位对齐
- [ ] 记一笔页面：借方合计 = 贷方合计时整块变黄
- [ ] 账目表格：借/贷标签黑白黄三色可一眼区分
- [ ] 总览对账卡片：差额 0 时绿底，非 0 时红底
- [ ] 键盘 Tab 能走完全部交互元素，焦点清晰可见
- [ ] 1280 宽下无横向滚动；账目表在窄屏横向滚动但不破版
- [ ] 浏览器控制台无报错
- [ ] 后端回归测试仍 69 项全通过（确认未误伤接口）

---

## 8. 待你确认的三点

1. **配色与字体已按技能输出定稿**（§3.1 / §3.3）—— 黄 `#FFEB3B` + 红 `#FF5252` + 蓝 `#2196F3` + 纯黑边，
   Fira Code + Fira Sans。若你想换成我原稿那套更克制的配色，告诉我即可，但技能推荐的是现在这套。
2. **深色版还是浅色版**？技能标注 `Neubrutalism` 的 Light 与 Dark **都支持**。
   本文档默认**浅色**（白底黑框 + 高饱和块），这是技能 ASCII 输出的原始推荐。
3. **改造范围**？建议**全站一次性改造**（5 个页面），避免风格割裂。
   若想先看效果，也可以只改「记一笔」+「总览」两页，确认方向后再铺开。

---

## 9. 与联网改造的关系（重要）

前端 Brutalism 改造**应当与「切回 Vite 构建」合并进行**，否则会返工：

| 做法 | 后果 |
| --- | --- |
| ❌ 先在免构建版上做 Brutalism，之后切 Vite | 样式与模板要迁移两遍 |
| ✅ 先切回 Vite + Element Plus + ECharts，再做 Brutalism | 一次到位 |

原因见 [网络环境说明](网络环境说明.md)：当前前端是免构建的降级形态
（模板是 JS 字符串、手写 CSS、无 ECharts）。Brutalism 需要改造的组件
（按钮、卡片、表单、表格、标签）恰好是 Element Plus 会整体替换的部分。

**建议顺序**：

1. 按 [网络环境说明](网络环境说明.md) 恢复联网能力（华为云 HTTP 镜像已实测可用）
2. 前端切回 Vite，把 `app.js` 拆成 `.vue` 组件，引入 Element Plus + ECharts
3. 按本文档 §3 的令牌覆盖 Element Plus 主题变量，实现 Brutalism
4. 用 ECharts 实现消费分析图表

这样 Brutalism 只需做一遍，且能作用在真正的组件体系上。

