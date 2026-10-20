<script setup>
/**
 * 借方 / 贷方两侧对照预览 —— 全站唯一"讲故事"的组件。
 *
 * 复式记账的核心是「每笔业务至少两条分录，借方合计 = 贷方合计」。
 * 这个组件把它变成一眼可见的界面元素：左右两张卡片，金额相等时两张卡
 * 都带绿色柔光，不等时转红。
 *
 * 视觉上从 Brutalism 的「3px 黑竖线 + 硬色底」改为 Claymorphism 的
 * 「两张浮起的小卡片 + 中间留白」，但**设计意图不变** ——
 * 用户每记一笔都能看见借贷如何配平。
 *
 * 文案面向普通用户：不出现「借 / 贷」，也不用「支出方 / 收入方」这种会误导的说法 ——
 * 支出业务的贷方是资产减少（钱付出去），叫它「收入方」会让用户以为收到了钱。
 * 两侧标题由 entryRoleLabel 按业务类型给出，见 utils/format.js。
 *
 * 注意：合计只用 parseFloat 做**判断**，展示仍走 formatMoney（字符串），
 * 不把浮点结果回传给后端。
 */
import { computed } from 'vue'
import { formatMoney, entryRoleLabel } from '@/utils/format'

const props = defineProps({
  lines: {
    type: Array,
    default: () => []
  },
  /**
   * 业务类型，决定两侧标题叫什么。
   * 支出 -> 支出分类 / 付款账户；收入 -> 收进账户 / 收入来源；
   * 转账 -> 转入账户 / 转出账户。
   */
  ledgerType: {
    type: String,
    default: 'EXPENSE'
  }
})

const debitSideLabel = computed(() => entryRoleLabel('DEBIT', props.ledgerType))
const creditSideLabel = computed(() => entryRoleLabel('CREDIT', props.ledgerType))

const totals = computed(() => {
  let debit = 0
  let credit = 0
  for (const l of props.lines) {
    const v = parseFloat(l.amount) || 0
    if (l.direction === 'DEBIT') debit += v
    else credit += v
  }
  return { debit: debit.toFixed(2), credit: credit.toFixed(2) }
})

const balanced = computed(
  () => props.lines.length >= 2 && totals.value.debit === totals.value.credit
)

const debitLine = computed(() => props.lines.find((l) => l.direction === 'DEBIT'))
const creditLine = computed(() => props.lines.find((l) => l.direction === 'CREDIT'))
</script>

<template>
  <div
    v-if="lines.length"
    class="scale"
    :class="balanced ? 'scale--balanced' : 'scale--unbalanced'"
  >
    <div class="scale__head">这笔账会这样记录</div>

    <div class="scale__cols">
      <div class="scale__col">
        <div class="scale__side">{{ debitSideLabel }}</div>
        <div class="scale__subject">{{ debitLine?.subjectName || '—' }}</div>
        <div class="scale__amount">{{ formatMoney(debitLine?.amount) }}</div>
      </div>
      <div class="scale__col">
        <div class="scale__side">{{ creditSideLabel }}</div>
        <div class="scale__subject">{{ creditLine?.subjectName || '—' }}</div>
        <div class="scale__amount">{{ formatMoney(creditLine?.amount) }}</div>
      </div>
    </div>

    <div class="scale__foot">
      <span class="muted">{{ formatMoney(totals.debit) }} ＝ {{ formatMoney(totals.credit) }}</span>
      <span class="scale__verdict">{{ balanced ? '✓ 金额相等' : '✗ 金额不等' }}</span>
    </div>
  </div>
</template>
