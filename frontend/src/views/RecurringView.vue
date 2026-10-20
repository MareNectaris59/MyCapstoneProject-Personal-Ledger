<script setup>
/**
 * 定期记账。
 *
 * 页面只有两件事：**看规则**（下一条什么时候记、记多少）和**加规则**。
 * 刻意不做图表 —— 这一页的价值在于"配好就不用管"，不是分析。
 *
 * 文案只说人话：不出现"规则""频率""CRON"这类系统词，
 * 而是「每月 15 日」「每周」这种用户本来就用来描述生活的话。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, today } from '@/utils/format'

const auth = useAuthStore()
const { accounts, expenseLeaves, incomeLeaves } = storeToRefs(auth)

const rules = ref([])
const loading = ref(false)
const busy = ref(false)
const showingForm = ref(false)

const form = reactive({
  name: '',
  ledgerType: 'EXPENSE',
  amount: '',
  subjectId: '',
  counterSubjectId: '',
  remark: '',
  frequency: 'MONTHLY',
  anchorDay: '',
  startDate: today(),
  endDate: ''
})

/** 支出时选费用分类；收入时选收入分类；转账时选转出账户。 */
const subjectOptions = computed(() => {
  if (form.ledgerType === 'EXPENSE') return expenseLeaves.value
  if (form.ledgerType === 'INCOME') return incomeLeaves.value
  return accounts.value
})

const subjectLabel = computed(() => {
  if (form.ledgerType === 'EXPENSE') return '支出分类'
  if (form.ledgerType === 'INCOME') return '收入分类'
  return '从哪个账户转出'
})

const counterLabel = computed(() => {
  if (form.ledgerType === 'EXPENSE') return '从哪个账户付'
  if (form.ledgerType === 'INCOME') return '收到哪个账户'
  return '转到哪个账户'
})

/** 切换类型时清空已选科目：旧选择在新类型下几乎必然是错的。 */
function onTypeChange() {
  form.subjectId = ''
  form.counterSubjectId = ''
}

/** 把频率翻译成用户自己的说法。 */
function frequencyText(rule) {
  if (rule.frequency === 'WEEKLY') return '每周一次'
  if (rule.frequency === 'MONTHLY') return `每月 ${rule.anchorDay || '—'} 日`
  return rule.frequency
}

const typeLabel = { EXPENSE: '支出', INCOME: '收入', TRANSFER: '转账' }

const canSubmit = computed(
  () =>
    form.name.trim() &&
    form.subjectId &&
    form.counterSubjectId &&
    Number(form.amount) > 0 &&
    form.startDate
)

async function load() {
  loading.value = true
  try {
    rules.value = await api.recurringList()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    loading.value = false
  }
}

async function submit() {
  busy.value = true
  try {
    await api.recurringCreate({
      name: form.name.trim(),
      ledgerType: form.ledgerType,
      amount: form.amount,
      subjectId: Number(form.subjectId),
      counterSubjectId: Number(form.counterSubjectId),
      remark: form.remark.trim() || null,
      frequency: form.frequency,
      anchorDay:
        form.frequency === 'MONTHLY' && form.anchorDay
          ? Number(form.anchorDay)
          : null,
      startDate: form.startDate,
      endDate: form.endDate || null
    })
    auth.notify('ok', '已添加')
    showingForm.value = false
    form.name = ''
    form.amount = ''
    form.remark = ''
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

async function toggle(rule) {
  try {
    await api.recurringUpdate(rule.id, { enabled: !rule.enabled })
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

async function remove(rule) {
  if (!confirm(`删除「${rule.name}」？已经记过的账不受影响。`)) return
  try {
    await api.recurringDelete(rule.id)
    auth.notify('ok', '已删除')
    await load()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

/** 立即执行到期的规则：不用等到凌晨。 */
async function runNow() {
  busy.value = true
  try {
    const r = await api.recurringRun()
    if (r.generated > 0) {
      auth.notify('ok', `已补记 ${r.generated} 笔`)
      await load()
    } else if (r.skipped > 0) {
      auth.notify('info', '这些账已经记过了，没有新增')
    } else {
      auth.notify('info', '还没有到期的规则')
    }
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-head">
    <div>
      <h1 class="page-title">定期记账</h1>
      <p class="page-sub">房租、宽带、会员这些固定支出，到点自动记</p>
    </div>
    <div class="row" style="gap: 8px">
      <button class="btn--ghost" :disabled="busy" @click="runNow">立即执行</button>
      <button @click="showingForm = !showingForm">
        {{ showingForm ? '收起' : '+ 新建' }}
      </button>
    </div>
  </div>

  <!-- 新建表单 -->
  <div v-if="showingForm" class="panel">
    <div class="panel__head"><span>新建一条</span></div>
    <div class="panel__body">
      <form @submit.prevent="submit">
        <div class="row">
          <div class="field">
            <label class="field__label" for="r-name">叫什么</label>
            <input id="r-name" v-model="form.name" placeholder="房租" maxlength="64" />
          </div>
          <div class="field">
            <label class="field__label" for="r-type">类型</label>
            <select id="r-type" v-model="form.ledgerType" @change="onTypeChange">
              <option value="EXPENSE">支出</option>
              <option value="INCOME">收入</option>
              <option value="TRANSFER">转账</option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="r-amount">金额</label>
            <input id="r-amount" v-model="form.amount" placeholder="2800.00" inputmode="decimal" />
          </div>
        </div>

        <div class="row">
          <div class="field">
            <label class="field__label" for="r-subject">{{ subjectLabel }}</label>
            <select id="r-subject" v-model="form.subjectId">
              <option value="" disabled>请选择</option>
              <option v-for="s in subjectOptions" :key="s.id" :value="s.id">
                {{ s.name }}
              </option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="r-counter">{{ counterLabel }}</label>
            <select id="r-counter" v-model="form.counterSubjectId">
              <option value="" disabled>请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
        </div>

        <div class="row">
          <div class="field">
            <label class="field__label" for="r-freq">多久一次</label>
            <select id="r-freq" v-model="form.frequency">
              <option value="MONTHLY">每月</option>
              <option value="WEEKLY">每周</option>
            </select>
          </div>
          <div v-if="form.frequency === 'MONTHLY'" class="field">
            <label class="field__label" for="r-anchor">每月几号</label>
            <input
              id="r-anchor"
              v-model="form.anchorDay"
              placeholder="留空则与首次执行日同日"
              inputmode="numeric"
              maxlength="2"
            />
          </div>
          <div class="field">
            <label class="field__label" for="r-start">从哪天开始</label>
            <input id="r-start" v-model="form.startDate" type="date" />
            <div class="field__hint">选以前的日期会把之前每一期都补记上</div>
          </div>
          <div class="field">
            <label class="field__label" for="r-end">到哪天结束</label>
            <input id="r-end" v-model="form.endDate" type="date" />
            <div class="field__hint">不填就是长期有效</div>
          </div>
        </div>

        <div class="field">
          <label class="field__label" for="r-remark">备注</label>
          <input id="r-remark" v-model="form.remark" placeholder="不填就用名称" maxlength="255" />
        </div>

        <div class="row">
          <button type="submit" :disabled="busy || !canSubmit">
            {{ busy ? '保存中…' : '保存' }}
          </button>
          <button type="button" class="btn--ghost" @click="showingForm = false">取消</button>
        </div>
      </form>
    </div>
  </div>

  <!-- 规则列表 -->
  <div class="panel">
    <div class="panel__head">
      <span>已设置 {{ rules.length }} 条</span>
    </div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!rules.length" class="empty">
        还没有定期记账。点右上「+ 新建」把房租、会员费交给它。
      </div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th class="num">金额</th>
            <th>多久一次</th>
            <th>下次记账</th>
            <th>状态</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rules" :key="r.id">
            <td>
              {{ r.name }}
              <div class="faint" style="font-size: 12px">
                {{ r.subjectName }} → {{ r.counterSubjectName }}
              </div>
            </td>
            <td><span class="tag">{{ typeLabel[r.ledgerType] }}</span></td>
            <td class="num">{{ formatMoney(r.amount) }}</td>
            <td>{{ frequencyText(r) }}</td>
            <td class="mono">{{ r.nextRunDate }}</td>
            <td>
              <span class="tag" :class="r.enabled ? 'tag--posted' : 'tag--reversed'">
                {{ r.enabled ? '启用' : '已停用' }}
              </span>
            </td>
            <td class="right">
              <button class="btn--ghost btn--sm" @click="toggle(r)">
                {{ r.enabled ? '停用' : '启用' }}
              </button>
              <button class="btn--ghost btn--sm" @click="remove(r)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
