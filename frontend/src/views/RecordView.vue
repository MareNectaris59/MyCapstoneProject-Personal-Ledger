<script setup>
/**
 * 记一笔：支出 / 收入 / 转账，带实时天平式分录预览。
 *
 * 三种业务的分录结构（见 docs/系统设计说明书.md §5.1.3）：
 *   支出   借 费用科目 ／ 贷 资产科目
 *   收入   借 资产科目 ／ 贷 收入科目
 *   转账   借 转入资产 ／ 贷 转出资产
 * 预览由 BalanceScale 渲染，金额一变就重算，用户按下"记账"前就能看到是否平衡。
 */
import { ref, reactive, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { today } from '@/utils/format'
import BalanceScale from '@/components/BalanceScale.vue'

const auth = useAuthStore()
const { accounts, expenseLeaves, incomeLeaves } = storeToRefs(auth)

const TABS = [
  { key: 'expense', label: '支出' },
  { key: 'income', label: '收入' },
  { key: 'transfer', label: '转账' }
]

const tab = ref('expense')
const busy = ref(false)

const form = reactive({
  amount: '',
  expenseSubjectId: '',
  incomeSubjectId: '',
  assetSubjectId: '',
  fromSubjectId: '',
  toSubjectId: '',
  occurredDate: today(),
  remark: ''
})

const accountName = (id) => auth.accountName(id)
const leafName = (leaves, id) => leaves.find((l) => l.id === Number(id))?.name || '—'

/** 实时分录预览。数据不全时返回空数组，BalanceScale 会自行隐藏。 */
const preview = computed(() => {
  const amount = form.amount || '0.00'

  if (tab.value === 'expense') {
    if (!form.expenseSubjectId || !form.assetSubjectId) return []
    return [
      { direction: 'DEBIT', subjectName: leafName(expenseLeaves.value, form.expenseSubjectId), amount },
      { direction: 'CREDIT', subjectName: accountName(form.assetSubjectId), amount }
    ]
  }

  if (tab.value === 'income') {
    if (!form.incomeSubjectId || !form.assetSubjectId) return []
    return [
      { direction: 'DEBIT', subjectName: accountName(form.assetSubjectId), amount },
      { direction: 'CREDIT', subjectName: leafName(incomeLeaves.value, form.incomeSubjectId), amount }
    ]
  }

  if (!form.fromSubjectId || !form.toSubjectId) return []
  return [
    { direction: 'DEBIT', subjectName: accountName(form.toSubjectId), amount },
    { direction: 'CREDIT', subjectName: accountName(form.fromSubjectId), amount }
  ]
})

function validate() {
  const amount = form.amount.trim()
  if (!amount || Number.isNaN(parseFloat(amount))) {
    return '请输入有效金额'
  }
  if (tab.value === 'expense') {
    if (!form.expenseSubjectId) return '请选择支出科目'
    if (!form.assetSubjectId) return '请选择付款账户'
  } else if (tab.value === 'income') {
    if (!form.assetSubjectId) return '请选择收款账户'
    if (!form.incomeSubjectId) return '请选择收入科目'
  } else {
    if (!form.fromSubjectId) return '请选择转出账户'
    if (!form.toSubjectId) return '请选择转入账户'
    if (Number(form.fromSubjectId) === Number(form.toSubjectId)) {
      return '转出与转入账户不能相同'
    }
  }
  return ''
}

async function submit() {
  const problem = validate()
  if (problem) {
    auth.notify('error', problem)
    return
  }

  const common = { amount: form.amount.trim(), occurredDate: form.occurredDate, remark: form.remark }
  let request

  if (tab.value === 'expense') {
    request = api.postExpense({
      ...common,
      expenseSubjectId: Number(form.expenseSubjectId),
      assetSubjectId: Number(form.assetSubjectId)
    })
  } else if (tab.value === 'income') {
    request = api.postIncome({
      ...common,
      assetSubjectId: Number(form.assetSubjectId),
      incomeSubjectId: Number(form.incomeSubjectId)
    })
  } else {
    request = api.postTransfer({
      ...common,
      fromSubjectId: Number(form.fromSubjectId),
      toSubjectId: Number(form.toSubjectId)
    })
  }

  busy.value = true
  try {
    const detail = await request
    auth.notify('ok', `记账成功 · 单据号 ${detail.ledgerNo}`)

    // 只清金额与备注，保留科目/账户选择 —— 连续记同类账时省一次点击
    form.amount = ''
    form.remark = ''
    await auth.loadSubjects()
  } catch (e) {
    const hint = e.traceId ? `（traceId ${e.traceId.slice(0, 8)}）` : ''
    auth.notify('error', `${e.message}${hint}`)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page-head">
    <h2>记一笔</h2>
  </div>

  <div class="panel" style="max-width: 680px">
    <div class="panel__body">
      <div class="row" style="margin-bottom: 16px">
        <button
          v-for="t in TABS"
          :key="t.key"
          :class="tab === t.key ? '' : 'btn--ghost'"
          @click="tab = t.key"
        >
          {{ t.label }}
        </button>
      </div>

      <form @submit.prevent="submit">
        <div class="field field--amount">
          <label class="field__label" for="amount">金额</label>
          <input
            id="amount"
            v-model="form.amount"
            inputmode="decimal"
            placeholder="0.00"
            autocomplete="off"
          />
          <div class="field__hint">最多两位小数，单笔上限 1,000,000.00</div>
        </div>

        <template v-if="tab === 'expense'">
          <div class="field">
            <label class="field__label" for="expense-subject">支出科目（消费分类）</label>
            <select id="expense-subject" v-model="form.expenseSubjectId">
              <option value="">请选择</option>
              <option v-for="s in expenseLeaves" :key="s.id" :value="s.id">
                {{ s.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="expense-asset">付款账户</label>
            <select id="expense-asset" v-model="form.assetSubjectId">
              <option value="">请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
        </template>

        <template v-else-if="tab === 'income'">
          <div class="field">
            <label class="field__label" for="income-asset">收款账户</label>
            <select id="income-asset" v-model="form.assetSubjectId">
              <option value="">请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="income-subject">收入科目</label>
            <select id="income-subject" v-model="form.incomeSubjectId">
              <option value="">请选择</option>
              <option v-for="s in incomeLeaves" :key="s.id" :value="s.id">
                {{ s.label }}
              </option>
            </select>
          </div>
        </template>

        <template v-else>
          <div class="field">
            <label class="field__label" for="from-account">转出账户</label>
            <select id="from-account" v-model="form.fromSubjectId">
              <option value="">请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="to-account">转入账户</label>
            <select id="to-account" v-model="form.toSubjectId">
              <option value="">请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
            <div class="field__hint">
              转账只是在两个账户之间挪钱，不算消费，也不会影响预算
            </div>
          </div>
        </template>

        <div class="field">
          <label class="field__label" for="date">日期</label>
          <input id="date" v-model="form.occurredDate" type="date" />
        </div>

        <div class="field">
          <label class="field__label" for="remark">备注</label>
          <input id="remark" v-model="form.remark" placeholder="选填" maxlength="255" />
        </div>

        <BalanceScale :lines="preview" />

        <div class="mt-4">
          <button type="submit" :disabled="busy">
            {{ busy ? '记账中…' : '记账' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
