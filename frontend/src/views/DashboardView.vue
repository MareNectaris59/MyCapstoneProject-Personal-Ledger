<script setup>
/**
 * 总览。
 *
 * 这一页回答用户打开应用最想知道的四件事：
 *   1. 我总共有多少钱
 *   2. 这个月花了多少
 *   3. 最近记了什么
 *   4. 钱在哪个账户（顺便增删改账户）
 *
 * 上一版这里有一整张"账目检查"卡片（4 个提示块 + 一段原理解释），
 * 现已压缩为底部一行安静的状态。理由见 docs/前端改版方案.md §4.1：
 * 自检是内部机制，日常不该占版面，但它证明的"借贷一致"值得保留可见性。
 *
 * 科目/账户的内部概念（编码、类型、金额增加方向）一律不出现 ——
 * 用户只需要"账户名 + 类型 + 余额"。
 */
import { ref, computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, isNegative, today, currentMonth, LEDGER_TYPE_LABEL } from '@/utils/format'

const auth = useAuthStore()
const { accounts } = storeToRefs(auth)

/* ---------------- 概览数据 ---------------- */
const monthExpense = ref('0.00')
const recent = ref([])
const check = ref(null)
const checkError = ref('')

const totalAssets = computed(() =>
  accounts.value.reduce((sum, a) => sum + (parseFloat(a.balance) || 0), 0)
)

async function loadOverview() {
  const month = currentMonth()
  try {
    // 后端没有 /summary 接口，直接取最近一段记录在前端归集。
    // 个人记账的量级下这样最省事，也不必为展示新增后端接口。
    const page = await api.ledgers({ page: 1, size: 100 })
    const records = page.records || []

    recent.value = records.slice(0, 5)

    const sum = records
      .filter((r) => r.ledgerType === 'EXPENSE'
        && r.state !== 'REVERSED'
        && (r.occurredDate || '').startsWith(month))
      .reduce((acc, r) => acc + (parseFloat(r.amount) || 0), 0)
    monthExpense.value = sum.toFixed(2)
  } catch (e) {
    auth.notify('error', e.message)
  }
}

async function runCheck() {
  checkError.value = ''
  try {
    check.value = await api.balanceCheck()
  } catch (e) {
    checkError.value = e.message
  }
}

/* ---------------- 新增账户 ---------------- */
const ACCOUNT_KINDS = [
  { value: 'BANK', label: '银行卡' },
  { value: 'WECHAT', label: '微信' },
  { value: 'ALIPAY', label: '支付宝' },
  { value: 'CASH', label: '现金' },
  { value: 'SAVING', label: '储蓄' },
  { value: 'OTHER', label: '其他' }
]

const showAdd = ref(false)
const adding = ref(false)
const newAccount = ref({ name: '', kind: 'BANK' })

function openAdd() {
  newAccount.value = { name: '', kind: 'BANK' }
  showAdd.value = true
}

async function saveAccount() {
  const name = newAccount.value.name.trim()
  if (!name) {
    auth.notify('error', '请输入账户名称')
    return
  }
  adding.value = true
  try {
    await api.createSubject({
      name,
      subjectType: 'ASSET',
      accountKind: newAccount.value.kind
    })
    auth.notify('ok', `已添加账户「${name}」`)
    showAdd.value = false
    await auth.loadSubjects()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    adding.value = false
  }
}

/* ---------------- 账户操作 ---------------- */
const openingTarget = ref(null)
const openingAmount = ref('')
const openingDate = ref(today())
const saving = ref(false)

function openOpening(account) {
  openingTarget.value = account
  openingAmount.value = ''
  openingDate.value = today()
}

async function saveOpening() {
  const amount = openingAmount.value.trim()
  if (!amount || Number.isNaN(parseFloat(amount))) {
    auth.notify('error', '请输入有效金额')
    return
  }
  saving.value = true
  try {
    await api.openingBalance(openingTarget.value.id, { amount, occurredDate: openingDate.value })
    auth.notify('ok', `已为「${openingTarget.value.name}」设置初始余额`)
    openingTarget.value = null
    await Promise.all([auth.loadSubjects(), loadOverview()])
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    saving.value = false
  }
}

async function disableAccount(account) {
  try {
    await api.setSubjectEnabled(account.id, false)
    auth.notify('ok', `已停用「${account.name}」`)
    await auth.loadSubjects()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

async function removeAccount(account) {
  if (!window.confirm(`确定删除账户「${account.name}」？\n\n只有余额为 0 且从未记过账的账户才能删除。`)) {
    return
  }
  try {
    await api.deleteSubject(account.id)
    auth.notify('ok', `已删除「${account.name}」`)
    await auth.loadSubjects()
  } catch (e) {
    // 后端在"已被账目使用"时返回 1303，这里如实转达
    auth.notify('error', e.message)
  }
}

onMounted(() => {
  loadOverview()
  runCheck()
})
</script>

<template>
  <div class="page-head">
    <h2>总览</h2>
    <button v-if="!showAdd" class="btn--ghost btn--sm" @click="openAdd">+ 添加账户</button>
  </div>

  <div class="grid grid--3" style="margin-bottom: 16px">
    <div class="stat">
      <div class="stat__label">账户总余额</div>
      <div
        class="stat__value"
        :class="{
          'stat__value--neg': isNegative(totalAssets),
          'stat__value--pos': !isNegative(totalAssets) && totalAssets > 0
        }"
      >
        {{ formatMoney(totalAssets.toFixed(2)) }}
      </div>
    </div>

    <div class="stat">
      <div class="stat__label">本月支出</div>
      <div class="stat__value stat__value--neg">{{ formatMoney(monthExpense) }}</div>
    </div>

    <div class="stat">
      <div class="stat__label">账户数量</div>
      <div class="stat__value">{{ accounts.length }}</div>
    </div>
  </div>

  <!-- 新增账户 -->
  <div v-if="showAdd" class="panel">
    <div class="panel__head">添加账户</div>
    <div class="panel__body">
      <div class="row">
        <div class="field mb-0">
          <label class="field__label" for="acc-name">账户名称</label>
          <input
            id="acc-name"
            v-model="newAccount.name"
            placeholder="如：招商银行卡"
            @keyup.enter="saveAccount"
          />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="acc-kind">类型</label>
          <select id="acc-kind" v-model="newAccount.kind">
            <option v-for="k in ACCOUNT_KINDS" :key="k.value" :value="k.value">
              {{ k.label }}
            </option>
          </select>
        </div>
        <button :disabled="adding" @click="saveAccount">
          {{ adding ? '添加中…' : '添加' }}
        </button>
        <button class="btn--ghost" @click="showAdd = false">取消</button>
      </div>
    </div>
  </div>

  <!-- 账户列表 -->
  <div class="panel">
    <div class="panel__head">资金账户</div>
    <div class="panel__body" style="padding-top: 0">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>账户</th>
              <th>类型</th>
              <th class="num">余额</th>
              <th class="right">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in accounts" :key="a.id">
              <td>{{ a.name }}</td>
              <td>
                <span class="tag">
                  {{ (ACCOUNT_KINDS.find(k => k.value === a.accountKind) || {}).label || a.accountKind }}
                </span>
              </td>
              <td class="num" :class="{ 'stat__value--neg': isNegative(a.balance) }">
                {{ formatMoney(a.balance) }}
              </td>
              <td class="right" style="white-space: nowrap">
                <button class="btn--ghost btn--sm" @click="openOpening(a)">初始余额</button>
                <button class="btn--ghost btn--sm" style="margin-left: 6px" @click="disableAccount(a)">
                  停用
                </button>
                <button class="btn--ghost btn--sm" style="margin-left: 6px" @click="removeAccount(a)">
                  删除
                </button>
              </td>
            </tr>
            <tr v-if="!accounts.length">
              <td colspan="4" class="empty">
                还没有账户，点右上角「+ 添加账户」开始
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- 设置初始余额 -->
  <div v-if="openingTarget" class="panel">
    <div class="panel__head">「{{ openingTarget.name }}」的初始余额</div>
    <div class="panel__body">
      <div class="alert alert--info">
        填入这个账户现在实际有多少钱，作为记账的起点。
      </div>
      <div class="row">
        <div class="field field--amount mb-0">
          <label class="field__label" for="ob-amount">金额</label>
          <input id="ob-amount" v-model="openingAmount" inputmode="decimal" placeholder="1000.00" />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="ob-date">日期</label>
          <input id="ob-date" v-model="openingDate" type="date" />
        </div>
        <button :disabled="saving" @click="saveOpening">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="btn--ghost" @click="openingTarget = null">取消</button>
      </div>
    </div>
  </div>

  <!-- 最近记录 -->
  <div class="panel">
    <div class="panel__head">
      <span>最近记录</span>
      <RouterLink to="/ledgers" class="faint" style="font-size: 12px">查看全部 →</RouterLink>
    </div>
    <div class="panel__body" style="padding-top: 0">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>日期</th>
              <th>类型</th>
              <th class="num">金额</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in recent" :key="r.id">
              <td class="mono">{{ r.occurredDate }}</td>
              <td>
                <span class="tag" :class="'tag--' + r.ledgerType.toLowerCase()">
                  {{ LEDGER_TYPE_LABEL[r.ledgerType] || r.ledgerType }}
                </span>
              </td>
              <td class="num">{{ formatMoney(r.amount) }}</td>
              <td class="muted">{{ r.remark }}</td>
            </tr>
            <tr v-if="!recent.length">
              <td colspan="4" class="empty">
                还没有记录，去<RouterLink to="/record">记一笔</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- 一行状态：日常安静，异常时才醒目 -->
  <div v-if="checkError" class="alert alert--error">{{ checkError }}</div>
  <div v-else-if="check" class="status-line" :class="{ 'status-line--bad': !check.globalBalanced || !check.accountBalanced }">
    <span v-if="check.globalBalanced && check.accountBalanced">✓ 账目核对正常</span>
    <span v-else>✗ 账目核对发现异常</span>
    <button @click="runCheck">重新检查</button>
  </div>
</template>
