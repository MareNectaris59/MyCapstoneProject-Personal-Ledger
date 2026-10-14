<script setup>
/**
 * 支出 / 收入两侧对照预览。
 *
 * 把「借 费用 / 贷 资产」呈现为左右等宽、中间硬线的对照块：
 * 金额相等时整块黄底，不等时整块红底。
 *
 * 文案面向普通用户 —— 用「支出方 / 收入方」而不是「借方 / 贷方」。
 * 用户不懂会计术语，但能看懂"这笔钱从哪出、归到哪一类"。
 */
import { computed } from 'vue'
import { formatMoney } from '@/utils/format'

const props = defineProps({
  lines: {
    type: Array,
    default: () => []
  }
})

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
        <div class="scale__side">支出方</div>
        <div class="scale__subject">{{ debitLine?.subjectName || '—' }}</div>
        <div class="scale__amount">{{ formatMoney(debitLine?.amount) }}</div>
      </div>
      <div class="scale__col">
        <div class="scale__side">收入方</div>
        <div class="scale__subject">{{ creditLine?.subjectName || '—' }}</div>
        <div class="scale__amount">{{ formatMoney(creditLine?.amount) }}</div>
      </div>
    </div>

    <div class="scale__foot">
      <span>{{ formatMoney(totals.debit) }} ＝ {{ formatMoney(totals.credit) }}</span>
      <span class="scale__verdict">{{ balanced ? '✓ 金额相等' : '✗ 金额不等' }}</span>
    </div>
  </div>
</template>
