<script setup>
/**
 * 科目明细（FR-A-05）。
 *
 * 回答"这个分类/账户到底发生了什么"：当前余额、期初、以及逐笔变动。
 *
 * 两处刻意的呈现决定：
 *
 * 1. **余额优先用缓存值，但会核对明细合计**。账务系统最怕"两边算出来不一样还
 *    假装没事"。后端会返回 cachedBalance 与 signedSum 并给出 consistent，
 *    不一致时这里明确挂一条警告，而不是挑一个看起来正常的数字显示。
 *
 * 2. **方向用"进/出"而不是"借/贷"**。这是给普通人用的记账软件，
 *    借贷是会计术语，用户只需要知道这笔钱是进这个账户还是出这个账户。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, LEDGER_TYPE_LABEL, LEDGER_TYPE_CLASS, LEDGER_STATE_LABEL, LEDGER_STATE_CLASS } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const balance = ref(null)
const entries = ref([])
const loading = ref(false)
const error = ref('')

const subjectId = computed(() => Number(route.params.id))

/** 余额：缓存值优先；没有缓存（如费用类科目）就用分录合计 */
const currentBalance = computed(() => {
  const b = balance.value
  if (!b) return '0.00'
  return b.cachedBalance ?? b.signedSum ?? '0.00'
})

/** 缓存与明细合计不一致时才提示（费用/收入科目没有缓存，不算异常） */
const inconsistent = computed(() => balance.value && balance.value.consistent === false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [b, e] = await Promise.all([
      api.subjectBalance(subjectId.value),
      api.subjectEntries(subjectId.value, 100)
    ])
    balance.value = b
    entries.value = e || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/**
 * 分录方向。
 *
 * `signedAmount` 的约定是借方为正、贷方为负，但**同一个正数在不同类型科目里
 * 意味着完全不同的事**：资产科目 +100 是钱进来了，费用科目 +100 是花出去 100。
 * 所以措辞必须按科目类型区分，不能统一写"流入/流出" —— 在费用科目上写"流入"
 * 会让用户以为这笔钱回到了账上。
 *
 * 类型码取 balance 接口返回的 subjectTypeCode，而不是比对中文显示名：
 * 显示名一改就对不上，枚举名才是稳定的。
 */
function flowText(row) {
  const signed = parseFloat(row.signedAmount || 0)
  if (signed === 0) return '无影响'
  const positive = signed > 0
  switch (balance.value?.subjectTypeCode) {
    case 'EXPENSE':
      // 借方增加表示支出发生；贷方（冲正）表示支出被冲减
      return positive ? '记支出' : '冲减支出'
    case 'INCOME':
      return positive ? '冲减收入' : '记收入'
    default:
      // 资产 / 负债 / 权益：正数进、负数出
      return positive ? '流入' : '流出'
  }
}

function openLedger(id) {
  router.push({ path: '/ledgers', query: { highlight: id } })
}

watch(subjectId, load)
onMounted(load)
</script>

<template>
  <div class="page-head">
    <h2>{{ balance?.subjectName || '科目明细' }}</h2>
    <button class="btn--ghost btn--sm" @click="router.back()">返回</button>
  </div>

  <div v-if="error" class="alert alert--error">{{ error }}</div>
  <div v-if="loading" class="empty">加载中…</div>

  <template v-if="!loading && balance">
    <div class="grid grid--3" style="margin-bottom: 16px">
      <div class="stat">
        <div class="stat__label">当前余额</div>
        <div class="stat__value">{{ formatMoney(currentBalance) }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">期初余额</div>
        <div class="stat__value">{{ balance.openingBalance === null ? '—' : formatMoney(balance.openingBalance) }}</div>
      </div>
      <div class="stat">
        <div class="stat__label">类型</div>
        <div class="stat__value" style="font-size: var(--fs-lg)">{{ balance.subjectType }}</div>
      </div>
    </div>

    <div v-if="inconsistent" class="alert alert--error" style="margin-bottom: 16px">
      余额与明细合计不一致：缓存 {{ formatMoney(balance.cachedBalance) }}，
      明细合计 {{ formatMoney(balance.signedSum) }}。请联系维护者核对。
    </div>

    <div class="panel">
      <div class="panel__head">
        <span>资金变动</span>
        <span class="faint" style="font-size: 12px">最近 {{ entries.length }} 笔</span>
      </div>
      <div class="table-wrap">
        <div v-if="!entries.length" class="empty">这个科目还没有任何记录</div>
        <table v-else>
          <thead>
            <tr>
              <th>日期</th>
              <th>单据号</th>
              <th>类型</th>
              <th>状态</th>
              <th>方向</th>
              <th class="num">金额</th>
              <th>摘要</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in entries" :key="r.entryId" class="clickable" @click="openLedger(r.ledgerId)">
              <td class="mono">{{ r.occurredDate }}</td>
              <td class="mono">{{ r.ledgerNo }}</td>
              <td>
                <span class="tag" :class="LEDGER_TYPE_CLASS[r.ledgerType]">
                  {{ LEDGER_TYPE_LABEL[r.ledgerType] || r.ledgerType }}
                </span>
              </td>
              <td>
                <span class="tag" :class="LEDGER_STATE_CLASS[r.state]">
                  {{ LEDGER_STATE_LABEL[r.state] || r.state }}
                </span>
              </td>
              <td>{{ flowText(r) }}</td>
              <td class="num mono" :class="parseFloat(r.signedAmount) < 0 ? 'text-neg' : 'text-pos'">
                {{ formatMoney(r.amount) }}
              </td>
              <td class="muted">{{ r.summary || r.remark || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </template>
</template>

<style scoped>
.clickable { cursor: pointer; }
.clickable:hover { background: var(--surface-2); }
.text-pos { color: var(--success-text); }
.text-neg { color: var(--danger-text); }
</style>
