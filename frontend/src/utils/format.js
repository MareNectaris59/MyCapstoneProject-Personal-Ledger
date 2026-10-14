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
 * 用全角空格做缩进，保留层级观感。
 */
export function flattenLeaves(nodes, out = [], depth = 0) {
  for (const node of nodes || []) {
    if (node.leaf) {
      out.push({
        id: node.id,
        code: node.code,
        name: node.name,
        label: '\u3000'.repeat(depth) + node.name
      })
    } else if (node.children?.length) {
      flattenLeaves(node.children, out, depth + 1)
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
 * 借贷方向的用户可读名。
 *
 * 界面上不出现「借 / 贷」两个会计字，改用「支出方 / 收入方」——
 * 用户不需要懂会计术语，只要能看懂"这笔钱从哪出、归到哪一类"。
 */
export function directionLabel(direction) {
  return direction === 'DEBIT' ? '支出方' : '收入方'
}
