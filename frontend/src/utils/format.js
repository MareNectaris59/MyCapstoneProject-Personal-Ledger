/**
 * 展示层格式化工具。
 *
 * 重要：金额从后端拿到的是**字符串**（"30.00"）。格式化只做展示，
 * 绝不把结果回传给后端 —— 回传一律用原始字符串，避免浮点精度问题。
 */

/** 千分位金额。入参可以是字符串或数字，输出固定两位小数。 */
export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '0.00'
  let s = String(value)
  const negative = s.startsWith('-')
  if (negative) s = s.slice(1)

  const [intPart, decPart = '00'] = s.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${negative ? '-' : ''}${grouped}.${decPart.padEnd(2, '0').slice(0, 2)}`
}

/** 是否负数（透支、超支）。 */
export function isNegative(value) {
  return parseFloat(value) < 0
}

/** 今天的 yyyy-MM-dd。 */
export function today() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 当期月份 yyyy-MM。 */
export function currentMonth() {
  return today().slice(0, 7)
}

/**
 * 把科目树压平成叶子列表。
 *
 * 只有叶子科目可以记账（BR-10），所以记账表单的下拉框只应包含叶子。
 *
 * **标签用「父分类 / 子分类」路径，而不是空白缩进。**
 * 早先用 `'\u3000'.repeat(depth)` 做缩进，在原生 <select> 里会显得参差不齐：
 * 二级叶子（医疗健康）带 1 个缩进、三级叶子（早餐）带 2 个，两段选项的起始位置
 * 不同，看起来像没对齐。改用路径写法后没有前导空白，天然对齐，而且
 * 「餐饮 / 早餐」比单纯「早餐」更能说明它属于哪一类。
 *
 * 第一层是类型容器（费用 / 收入），不放进路径 ——
 * 否则会得到「费用 / 餐饮 / 早餐」这种啰嗦且没有信息量的标签。
 */
export function flattenLeaves(nodes, out = [], depth = 0, ancestors = []) {
  for (const node of nodes || []) {
    if (node.leaf) {
      out.push({
        id: node.id,
        code: node.code,
        name: node.name,
        label: [...ancestors, node.name].join(' / ')
      })
    } else if (node.children?.length) {
      const next = depth === 0 ? ancestors : [...ancestors, node.name]
      flattenLeaves(node.children, out, depth + 1, next)
    }
  }
  return out
}

/**
 * 账目类型的中文名。
 *
 * 面向普通用户，不用会计术语：
 *   OPENING  原为「期初建账」 → 「初始余额」
 *   REVERSAL 原为「冲正」     → 「撤销」
 */
export const LEDGER_TYPE_LABEL = {
  EXPENSE: '支出',
  INCOME: '收入',
  TRANSFER: '转账',
  OPENING: '初始余额',
  REVERSAL: '撤销'
}

/**
 * 账目状态的中文名。
 *
 *   PARTIALLY_REVERSED 原为「部分冲正」 → 「部分撤销」
 *   REVERSED           原为「已冲正」   → 「已撤销」
 *   CREATED 只存在于事务内，用户不会看到，保留通用文案即可
 */
export const LEDGER_STATE_LABEL = {
  CREATED: '处理中',
  POSTED: '已记账',
  PARTIALLY_REVERSED: '部分撤销',
  REVERSED: '已撤销'
}

/** 类型 → 标签样式类 */
export const LEDGER_TYPE_CLASS = {
  EXPENSE: 'tag--expense',
  INCOME: 'tag--income',
  TRANSFER: 'tag--transfer',
  OPENING: 'tag--opening',
  REVERSAL: 'tag--reversal'
}

/** 状态 → 标签样式类 */
export const LEDGER_STATE_CLASS = {
  CREATED: 'tag--partial',
  POSTED: 'tag--posted',
  PARTIALLY_REVERSED: 'tag--partial',
  REVERSED: 'tag--reversed'
}

/**
 * 一条分录在这笔业务里扮演的角色。
 *
 * ⚠️ **不能只按方向（借/贷）映射**。
 *
 * 借/贷是「方向」，而「支出方 / 收入方」是「语义」，两者并不一一对应。
 * 支出业务的分录是「借 费用 / 贷 资产」：
 *   借方那条是费用增加（支出确实发生了）；
 *   贷方那条是资产减少（钱从账户里付出去）—— 把它叫「收入方」，
 *   用户会以为自己收到了钱。这是个真实存在过的错误。
 *
 * 收入业务恰好反过来（借 资产 / 贷 收入）；转账两边都是资产账户
 * （借 转入 / 贷 转出），"支出/收入"这套说法同样不适用。
 * 所以必须结合业务类型判断，必要时再看科目类型。
 *
 * @param {string} direction   DEBIT | CREDIT
 * @param {string} ledgerType  EXPENSE | INCOME | TRANSFER | OPENING | REVERSAL
 * @param {string} [subjectType] ASSET | LIABILITY | EQUITY | INCOME | EXPENSE
 *                               仅 REVERSAL 时用来区分"退回账户"与"冲减支出"
 */
export function entryRoleLabel(direction, ledgerType, subjectType) {
  const isDebit = direction === 'DEBIT'

  // 冲正单的科目方向是原凭证的反面：资产退回叫"退回账户"，
  // 费用/收入被冲减则叫"冲减支出/收入"，只能靠科目类型区分。
  if (ledgerType === 'REVERSAL') {
    if (subjectType === 'ASSET') return isDebit ? '退回账户' : '扣回账户'
    if (subjectType === 'INCOME') return '冲减收入'
    return '冲减支出'
  }

  switch (ledgerType) {
    case 'OPENING':
      return isDebit ? '期初记入' : '期初来源'
    case 'INCOME':
      return isDebit ? '收进账户' : '收入来源'
    case 'TRANSFER':
      return isDebit ? '转入账户' : '转出账户'
    case 'EXPENSE':
    default:
      return isDebit ? '支出分类' : '付款账户'
  }
}
