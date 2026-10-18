<script setup>
/**
 * 预算。
 *
 * 这一页要回答三个问题，文案只服务于此：
 *   1. 这个月还能花多少
 *   2. 有没有超支
 *   3. 上期省下的钱有没有带过来
 *
 * 界面刻意不出现「结转模式」「可用额」这类内部口径词。FULL 与 SURPLUS_ONLY
 * 的区别对用户而言就是一句话：「上期超支要不要扣本期」，
 * 所以表单里直接用这句话做选项，而不是暴露枚举名。
 *
 * 三处来自后端口径的渲染约束：
 *   - executionRate 为 null 表示"本期没设额度"，显示「—」而不是 0%
 *   - availableAmount 超支时已被后端截断为 0，真实超支额在 overspent 字段，
 *     必须用 overspent 提示，否则用户看不到自己超了多少
 *   - 执行率可能超过 100%，进度条封顶但**数字照实显示**
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, today } from '@/utils/format'

const auth = useAuthStore()

const budgets = ref([])
const alerts = ref([])
const loading = ref(false)

/* ---------------- 表单 ---------------- */
const PERIOD_TYPES = [
  { value: 'MONTH', label: '每月' },
  { value: 'WEEK', label: '每周' },
  { value: 'CUSTOM', label: '自定义天数' }
]

/** 结转用「人话」表达，不暴露 NONE / SURPLUS_ONLY / FULL */
const ROLLOVER_MODES = [
  { value: 'SURPLUS_ONLY', label: '上期没花完的，本期可以继续花' },
  { value: 'NONE', label: '每期额度独立，不结转' },
  { value: 'FULL', label: '上期超支的，从本期额度里扣' }
]

const showForm = ref(false)
const editingId = ref(null)
const busy = ref(false)
const form = ref(blankForm())

function blankForm() {
  return {
    name: '',
    scopeType: 'TOTAL',
    subjectId: '',
    periodType: 'MONTH',
    periodValue: 7,
    startDate: today().slice(0, 8) + '01',
    endDate: '',
    amount: '',
    rolloverMode: 'SURPLUS_ONLY',
    alertThreshold: '80'
  }
}

/* 只有费用类叶子科目能设预算 —— 与后端 BUDGET_SUBJECT_TYPE_INVALID 一致。
   用叶子而不是全部费用科目：给「餐饮」这个分组设预算在其子树口径上是合法的，
   后端也允许，所以这里放宽到费用类科目全量。 */
const expenseLeaves = computed(() => auth.expenseLeaves || [])

function openCreate() {
  editingId.value = null
  form.value = blankForm()
  showForm.value = true
}

function openEdit(budget) {
  editingId.value = budget.id
  form.value = {
    name: budget.name,
    scopeType: budget.scopeType,
    subjectId: budget.subjectId || '',
    periodType: budget.periodType,
    periodValue: budget.periodValue || 7,
    startDate: budget.startDate,
    endDate: budget.endDate || '',
    amount: budget.amount,
    rolloverMode: budget.rolloverMode,
    alertThreshold: budget.alertThreshold
  }
  showForm.value = true
}

async function save() {
  const amount = String(form.value.amount || '').trim()
  if (!amount || Number.isNaN(parseFloat(amount))) {
    auth.notify('error', '请填写每期额度')
    return
  }
  if (editingId.value) {
    await saveEdit(amount)
  } else {
    await saveCreate(amount)
  }
}

async function saveCreate(amount) {
  busy.value = true
  try {
    const payload = {
      name: form.value.name.trim() || undefined,
      scopeType: form.value.scopeType,
      subjectId: form.value.scopeType === 'SUBJECT' ? Number(form.value.subjectId) : null,
      periodType: form.value.periodType,
      periodValue: form.value.periodType === 'CUSTOM' ? Number(form.value.periodValue) : null,
      startDate: form.value.startDate,
      endDate: form.value.endDate || null,
      amount,
      rolloverMode: form.value.rolloverMode,
      alertThreshold: String(form.value.alertThreshold || '80')
    }
    if (payload.scopeType === 'SUBJECT' && !payload.subjectId) {
      auth.notify('error', '请选择要设预算的分类')
      busy.value = false
      return
    }
    await api.createBudget(payload)
    auth.notify('ok', '预算已创建')
    showForm.value = false
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

async function saveEdit(amount) {
  busy.value = true
  try {
    // 只提交可改字段：周期结构不可变（后端会忽略，但前端也不该发）
    await api.updateBudget(editingId.value, {
      name: form.value.name.trim() || undefined,
      amount,
      rolloverMode: form.value.rolloverMode,
      alertThreshold: String(form.value.alertThreshold || '80')
    })
    auth.notify('ok', '预算已更新')
    showForm.value = false
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(budget) {
  try {
    await api.updateBudget(budget.id, { enabled: !budget.enabled })
    auth.notify('ok', budget.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

async function remove(budget) {
  if (!window.confirm(`删除预算「${budget.name}」？\n\n已产生的周期记录会保留，便于日后回溯。`)) {
    return
  }
  try {
    await api.deleteBudget(budget.id)
    auth.notify('ok', '预算已删除')
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

/* ---------------- 预警 ---------------- */
async function markRead(alert) {
  try {
    await api.readBudgetAlert(alert.id)
    await loadAlerts()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

/* ---------------- 展开周期趋势 ---------------- */
const expanded = ref(null)
const periods = ref([])

async function togglePeriods(budget) {
  if (expanded.value === budget.id) {
    expanded.value = null
    periods.value = []
    return
  }
  try {
    periods.value = await api.budgetPeriods(budget.id)
    expanded.value = budget.id
  } catch (e) {
    auth.notify('error', e.message)
  }
}

/* ---------------- 展示辅助 ---------------- */

/** 执行率 → 进度条百分比（封顶 100）与配色档位 */
function barStyle(rate) {
  if (rate === null || rate === undefined) return { width: '0%', cls: 'bar__fill--empty' }
  const n = parseFloat(rate)
  const width = Math.max(0, Math.min(100, n))
  let cls = ''
  if (n >= 100) cls = 'bar__fill--danger'
  else if (n >= 80) cls = 'bar__fill--warn'
  return { width: `${width}%`, cls }
}

function trendStyle(rate) {
  if (rate === null || rate === undefined) return { height: '3px', cls: 'trend__bar--empty' }
  const n = parseFloat(rate)
  const height = `${Math.max(3, Math.min(100, n))}%`
  let cls = ''
  if (n >= 100) cls = 'trend__bar--danger'
  else if (n >= 80) cls = 'trend__bar--warn'
  return { height, cls }
}

/** 执行率显示：null 是「没设额度」，不是 0% */
function rateText(rate) {
  return rate === null || rate === undefined ? '—' : `${rate}%`
}

function carryText(period) {
  const carry = parseFloat(period?.carryIn || '0')
  if (carry > 0) return `含上期结转 ${formatMoney(period.carryIn)}`
  if (carry < 0) return `已扣上期超支 ${formatMoney(Math.abs(carry).toFixed(2))}`
  return ''
}

/* ---------------- 加载 ---------------- */
async function load() {
  loading.value = true
  try {
    budgets.value = await api.budgets()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    loading.value = false
  }
}

async function loadAlerts() {
  try {
    alerts.value = await api.budgetAlerts()
  } catch {
    // 预警是次要信息，拉取失败不该打断主流程
    alerts.value = []
  }
}

onMounted(() => {
  auth.loadSubjects()
  load()
  loadAlerts()
})
</script>

<template>
  <div class="page-head">
    <h2>预算</h2>
    <button v-if="!showForm" class="btn--ghost btn--sm" @click="openCreate">+ 新建预算</button>
  </div>

  <!-- 未读预警 -->
  <div v-if="alerts.length" class="panel">
    <div class="panel__head">提醒</div>
    <div class="panel__body" style="padding-top: 0">
      <div v-for="a in alerts" :key="a.id" class="alert" :class="a.alertLevel === 'DANGER' ? 'alert--error' : 'alert--warn'">
        <div class="flex-between">
          <span>{{ a.message }}</span>
          <button class="btn--ghost btn--sm" @click="markRead(a)">知道了</button>
        </div>
      </div>
    </div>
  </div>

  <!-- 新建 / 编辑 -->
  <div v-if="showForm" class="panel">
    <div class="panel__head">{{ editingId ? '修改预算' : '新建预算' }}</div>
    <div class="panel__body">
      <div class="row">
        <div class="field mb-0">
          <label class="field__label" for="bud-name">名称（可留空）</label>
          <input id="bud-name" v-model="form.name" placeholder="如：每月吃饭" />
        </div>
        <div class="field field--amount mb-0">
          <label class="field__label" for="bud-amount">每期额度</label>
          <input id="bud-amount" v-model="form.amount" inputmode="decimal" placeholder="1000.00" />
        </div>
      </div>

      <!-- 周期结构只在新建时可设：改了会让已生成的周期与新窗口定义不一致 -->
      <template v-if="!editingId">
        <div class="row" style="margin-top: 16px">
          <div class="field mb-0">
            <label class="field__label" for="bud-scope">范围</label>
            <select id="bud-scope" v-model="form.scopeType">
              <option value="TOTAL">全部支出</option>
              <option value="SUBJECT">只算某个分类</option>
            </select>
          </div>
          <div v-if="form.scopeType === 'SUBJECT'" class="field mb-0">
            <label class="field__label" for="bud-subject">分类</label>
            <select id="bud-subject" v-model="form.subjectId">
              <option value="" disabled>请选择</option>
              <option v-for="s in expenseLeaves" :key="s.id" :value="s.id">{{ s.label }}</option>
            </select>
          </div>
          <div class="field mb-0">
            <label class="field__label" for="bud-period">周期</label>
            <select id="bud-period" v-model="form.periodType">
              <option v-for="p in PERIOD_TYPES" :key="p.value" :value="p.value">{{ p.label }}</option>
            </select>
          </div>
          <div v-if="form.periodType === 'CUSTOM'" class="field mb-0">
            <label class="field__label" for="bud-days">天数</label>
            <input id="bud-days" v-model="form.periodValue" type="number" min="1" />
          </div>
        </div>

        <div class="row" style="margin-top: 16px">
          <div class="field mb-0">
            <label class="field__label" for="bud-start">生效日</label>
            <input id="bud-start" v-model="form.startDate" type="date" />
          </div>
          <div class="field mb-0">
            <label class="field__label" for="bud-end">结束日（留空表示长期）</label>
            <input id="bud-end" v-model="form.endDate" type="date" />
          </div>
        </div>
      </template>

      <div class="field" style="margin-top: 16px">
        <label class="field__label" for="bud-rollover">没花完的钱怎么办</label>
        <select id="bud-rollover" v-model="form.rolloverMode">
          <option v-for="m in ROLLOVER_MODES" :key="m.value" :value="m.value">{{ m.label }}</option>
        </select>
      </div>

      <div class="field">
        <label class="field__label" for="bud-threshold">用到多少百分比时提醒我</label>
        <input id="bud-threshold" v-model="form.alertThreshold" inputmode="decimal" placeholder="80" />
      </div>

      <button :disabled="busy" @click="save">
        {{ busy ? '保存中…' : (editingId ? '保存修改' : '创建预算') }}
      </button>
      <button class="btn--ghost" style="margin-left: 8px" @click="showForm = false">取消</button>
    </div>
  </div>

  <!-- 预算列表 -->
  <div class="panel">
    <div class="panel__head">
      <span>我的预算</span>
      <span v-if="loading" class="faint" style="font-size: 12px">加载中…</span>
    </div>

    <div v-if="!budgets.length && !loading" class="empty">
      还没有预算。设一个，记账时就能看到还剩多少。
    </div>

    <div v-for="b in budgets" :key="b.id" class="budget">
      <div class="budget__head">
        <div>
          <div class="budget__name">
            {{ b.name }}
            <span v-if="!b.enabled" class="tag tag--reversal" style="margin-left: 6px">已停用</span>
          </div>
          <div class="budget__meta">
            {{ PERIOD_TYPES.find(p => p.value === b.periodType)?.label || b.periodType }}
            <template v-if="b.subjectName"> · {{ b.subjectName }}</template>
            <template v-if="b.period"> · 本期 {{ b.period.periodLabel }}</template>
          </div>
        </div>
        <div class="budget__actions">
          <button class="btn--ghost btn--sm" @click="togglePeriods(b)">
            {{ expanded === b.id ? '收起' : '明细' }}
          </button>
          <button class="btn--ghost btn--sm" @click="openEdit(b)">修改</button>
          <button class="btn--ghost btn--sm" @click="toggleEnabled(b)">
            {{ b.enabled ? '停用' : '启用' }}
          </button>
          <button class="btn--ghost btn--sm" @click="remove(b)">删除</button>
        </div>
      </div>

      <template v-if="b.period">
        <div class="bar">
          <div
            class="bar__fill"
            :class="barStyle(b.period.executionRate).cls"
            :style="{ width: barStyle(b.period.executionRate).width }"
          ></div>
        </div>

        <div class="budget__numbers">
          <span>已用 <b>{{ formatMoney(b.period.usedAmount) }}</b></span>
          <span>可用 <b>{{ formatMoney(b.period.availableAmount) }}</b></span>
          <span :class="{ over: parseFloat(b.period.remaining) < 0 }">
            剩余 <b>{{ formatMoney(b.period.remaining) }}</b>
          </span>
          <span>已用掉 <b>{{ rateText(b.period.executionRate) }}</b></span>
        </div>

        <!-- 超支时后端把可用额截断为 0，真实超支额在这里，必须显示 -->
        <div v-if="b.period.overspent" class="budget__numbers over">
          累计超支 <b>{{ formatMoney(b.period.overspent) }}</b>
        </div>

        <div v-if="carryText(b.period)" class="budget__carry">{{ carryText(b.period) }}</div>
      </template>
      <div v-else class="budget__meta" style="margin-top: 8px">
        预算尚未生效（生效日 {{ b.startDate }}）
      </div>

      <!-- 周期明细 -->
      <template v-if="expanded === b.id">
        <div class="trend">
          <div v-for="p in periods" :key="p.id" class="trend__col">
            <div class="trend__bar" :class="trendStyle(p.executionRate).cls" :style="{ height: trendStyle(p.executionRate).height }"></div>
            <div class="trend__label">{{ p.periodLabel.slice(-5) }}</div>
          </div>
        </div>
        <div class="table-wrap" style="margin-top: 12px">
          <table>
            <thead>
              <tr>
                <th>周期</th>
                <th class="num">额度</th>
                <th class="num">结转</th>
                <th class="num">已用</th>
                <th class="num">剩余</th>
                <th>执行率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in periods" :key="p.id">
                <td class="mono">{{ p.periodLabel }}</td>
                <td class="num">{{ formatMoney(p.baseAmount) }}</td>
                <td class="num">{{ formatMoney(p.carryIn) }}</td>
                <td class="num">{{ formatMoney(p.usedAmount) }}</td>
                <td class="num" :class="{ over: parseFloat(p.remaining) < 0 }">{{ formatMoney(p.remaining) }}</td>
                <td>{{ rateText(p.executionRate) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>
