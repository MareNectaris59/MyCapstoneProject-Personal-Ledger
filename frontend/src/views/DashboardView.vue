<script setup>
/**
 * 总览：账户余额 + 对账自检 + 期初建账。
 *
 * 页面重点是两块：
 *   1. 四个统计卡 —— 尤其"借贷平衡"与"余额一致性"，这是系统的自检结论
 *   2. 账户表 + 期初建账 —— 资产负债表能"平"的起点
 *
 * 对账卡片用整块底色表达结论：平衡则绿底，不平衡则红底。
 * 这是系统最重要的一项自检，值得最醒目的呈现。
 */
import { ref, computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, isNegative, today } from '@/utils/format'

const auth = useAuthStore()
const { accounts } = storeToRefs(auth)

const check = ref(null)
const checkError = ref('')

const openingTarget = ref(null)
const openingAmount = ref('')
const openingDate = ref(today())
const saving = ref(false)

const totalAssets = computed(() =>
  accounts.value.reduce((sum, a) => sum + (parseFloat(a.balance) || 0), 0)
)

async function runCheck() {
  checkError.value = ''
  try {
    check.value = await api.balanceCheck()
  } catch (e) {
    checkError.value = e.message
  }
}

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
    await api.openingBalance(openingTarget.value.id, {
      amount,
      occurredDate: openingDate.value
    })
    auth.notify('ok', `已为「${openingTarget.value.name}」建账`)
    openingTarget.value = null
    await auth.loadSubjects()
    await runCheck()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    saving.value = false
  }
}

onMounted(runCheck)
</script>

<template>
  <div class="page-head">
    <h2>总览</h2>
    <button class="btn btn--ghost btn--sm" @click="runCheck">重新检查</button>
  </div>

  <div class="grid grid--4 mt-0">
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
      <div class="stat__label">账户数量</div>
      <div class="stat__value">{{ accounts.length }}</div>
    </div>

    <div class="stat">
      <div class="stat__label">账目核对</div>
      <div
        class="stat__value"
        :class="check?.globalBalanced ? 'stat__value--pos' : 'stat__value--neg'"
      >
        {{ check ? (check.globalBalanced ? '正确' : '有误') : '—' }}
      </div>
    </div>

    <div class="stat">
      <div class="stat__label">余额核对</div>
      <div
        class="stat__value"
        :class="check?.accountBalanced ? 'stat__value--pos' : 'stat__value--neg'"
      >
        {{ check ? (check.accountBalanced ? '正确' : '有差异') : '—' }}
      </div>
    </div>
  </div>

  <div class="panel mt-4">
    <div class="panel__head">资金账户</div>
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
            <td><span class="tag">{{ a.accountKind }}</span></td>
            <td class="num" :class="{ 'stat__value--neg': isNegative(a.balance) }">
              {{ formatMoney(a.balance) }}
            </td>
            <td class="right">
              <button class="btn btn--ghost btn--sm" @click="openOpening(a)">设期初余额</button>
            </td>
          </tr>
          <tr v-if="!accounts.length">
            <td colspan="4" class="empty">暂无账户</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-if="openingTarget" class="panel">
    <div class="panel__head">为「{{ openingTarget.name }}」设置期初余额</div>
    <div class="panel__body">
      <div class="alert alert--info">
        填入这个账户现在实际有多少钱，作为记账的起点。
        以后每笔收支都会在上面加减，随时能看到准确余额。
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
          {{ saving ? '建账中…' : '建账' }}
        </button>
        <button class="btn btn--ghost" @click="openingTarget = null">取消</button>
      </div>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head">账目检查</div>
    <div class="panel__body">
      <div v-if="checkError" class="alert alert--error">{{ checkError }}</div>

      <template v-else-if="check">
        <div
          class="alert"
          :class="check.globalBalanced ? 'alert--ok' : 'alert--error'"
        >
          {{ check.globalBalanced
            ? '✓ 所有账目记录完整，收支两侧金额一致'
            : '✗ 发现金额不一致的账目，请联系技术支持' }}
        </div>

        <div
          class="alert"
          :class="check.accountBalanced ? 'alert--ok' : 'alert--error'"
        >
          {{ check.accountBalanced
            ? '✓ 各账户余额与全部收支记录核对一致'
            : `✗ 有 ${check.accountDrift.length} 个账户的余额对不上，建议重新检查` }}
        </div>

        <p class="muted mb-0" style="font-size: 12px">
          这两项检查是系统自动完成的，用来保证你看到的余额准确无误。
          若出现异常，请先到「账目」页确认最近的记录。
        </p>
      </template>

      <div v-else class="empty">正在检查…</div>
    </div>
  </div>
</template>
