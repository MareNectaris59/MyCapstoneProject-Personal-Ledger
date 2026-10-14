<script setup>
/**
 * 递归的科目树行。
 *
 * 用递归组件而不是把树压平成表格：科目树是"结构"本身，
 * 用缩进 + 树形引导符把层级关系直接画出来，比塞一个"层级"数字列更直观
 * —— 这也符合 Brutalism「结构外显」的主张。
 */
import { formatMoney } from '@/utils/format'

const props = defineProps({
  node: { type: Object, required: true }
})

const TYPE_LABEL = {
  ASSET: '资产',
  LIABILITY: '负债',
  EQUITY: '权益',
  INCOME: '收入',
  EXPENSE: '费用'
}
</script>

<template>
  <tr>
    <td class="mono">{{ node.code }}</td>
    <td>
      <!-- 缩进用 padding 而不是全角空格，保证数字列对齐不受层级影响 -->
      <span :style="{ paddingLeft: `${(node.level - 1) * 18}px` }">
        <span v-if="node.level > 1" class="muted">└ </span>
        <b v-if="!node.leaf">{{ node.name }}</b>
        <span v-else>{{ node.name }}</span>
      </span>
    </td>
    <td>
      <span class="tag">{{ TYPE_LABEL[node.subjectType] || node.subjectType }}</span>
    </td>
    <!-- 面向普通用户：不出现「借 / 贷」，改为金额增加时记在哪一侧 -->
    <td>{{ node.normalSide === 'DEBIT' ? '增加时记左边' : '增加时记右边' }}</td>
    <td>
      <span v-if="node.leaf" class="tag tag--posted">可以记账</span>
      <span v-else class="tag tag--reversal">不可记账</span>
    </td>
    <td class="num">
      <template v-if="node.balance !== null">{{ formatMoney(node.balance) }}</template>
      <span v-else class="muted">—</span>
    </td>
  </tr>

  <SubjectRow v-for="child in node.children || []" :key="child.id" :node="child" />
</template>
