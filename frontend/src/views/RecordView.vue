<script setup>
/**
 * 记一笔：支出 / 收入 / 转账，带实时分录预览。
 *
 * 三种业务的分录结构（见 docs/系统设计说明书.md §5.1.3）：
 *   支出   借 费用科目 ／ 贷 资产科目
 *   收入   借 资产科目 ／ 贷 收入科目
 *   转账   借 转入资产 ／ 贷 转出资产
 * 预览由 BalanceScale 渲染，金额一变就重算，用户按下"记账"前就能看到是否平衡。
 *
 * 这一页同时承担了「分类管理」的职责（原「分类与账户」页已删除）：
 * 分类下拉框本身就是分类列表，旁边一个「+ 新建」即可就地新增。
 * 这样用户不必为了加一个消费类别而跳到一个讲会计概念的页面去。
 */
import { ref, reactive, computed } from 'vue'
import { storeToRefs } from 'pinia'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { today } from '@/utils/format'
import BalanceScale from '@/components/BalanceScale.vue'

const auth = useAuthStore()
const { accounts, expenseLeaves, incomeLeaves, expenseTree, incomeTree } = storeToRefs(auth)

const TABS = [
  { key: 'expense', label: '支出' },
  { key: 'income', label: '收入' },
  { key: 'transfer', label: '转账' }
]

const tab = ref('expense')
const busy = ref(false)

/* ---------------- 就地新建分类 ---------------- */
const newCategory = reactive({ type: '', name: '', parentId: '' })
const savingCategory = ref(false)

/** 可作为父级的分类：树里非叶子的一级节点（餐饮、交通……） */
const expenseGroups = computed(() =>
  (expenseTree.value || []).filter((n) => !n.leaf).map((n) => ({ id: n.id, name: n.name }))
)

function openNewCategory(type) {
  newCategory.type = type
  newCategory.name = ''
  newCategory.parentId = ''
}

async function saveCategory() {
  const name = newCategory.name.trim()
  if (!name) {
    auth.notify('error', '请输入分类名称')
    return
  }
  savingCategory.value = true
  try {
    const created = await api.createSubject({
      name,
      subjectType: newCategory.type,
      parentId: newCategory.parentId ? Number(newCategory.parentId) : null
    })
    await auth.loadSubjects()
    // 创建后直接选中，用户不必再去下拉框里找
    if (newCategory.type === 'EXPENSE') form.expenseSubjectId = created.id
    else form.incomeSubjectId = created.id
    auth.notify('ok', `已创建分类「${name}」`)
    newCategory.type = ''
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    savingCategory.value = false
  }
}

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
            <div class="flex-between">
              <label class="field__label" for="expense-subject">支出分类</label>
              <button type="button" class="btn--ghost btn--sm" @click="openNewCategory('EXPENSE')">
                + 新建
              </button>
            </div>
            <select id="expense-subject" v-model="form.expenseSubjectId" :class="{ 'is-placeholder': !form.expenseSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="s in expenseLeaves" :key="s.id" :value="s.id">
                {{ s.label }}
              </option>
            </select>
          </div>

          <!-- 就地新建分类：不跳页、不暴露编码/层级等内部概念 -->
          <div v-if="newCategory.type === 'EXPENSE'" class="panel" style="box-shadow: var(--shadow-inset); background: var(--surface-2)">
            <div class="panel__body" style="padding: var(--sp-4)">
              <div class="field mb-0">
                <label class="field__label" for="new-cat-name">新分类名称</label>
                <input
                  id="new-cat-name"
                  v-model="newCategory.name"
                  placeholder="如：宠物、订阅服务"
                  @keyup.enter="saveCategory"
                />
              </div>
              <div class="field" style="margin-top: 12px">
                <label class="field__label" for="new-cat-parent">归到哪一类（可不选）</label>
                <select id="new-cat-parent" v-model="newCategory.parentId">
                  <option value="">作为一级分类</option>
                  <option v-for="p in expenseGroups" :key="p.id" :value="p.id">{{ p.name }}</option>
                </select>
              </div>
              <button type="button" :disabled="savingCategory" @click="saveCategory">
                {{ savingCategory ? '创建中…' : '创建并使用' }}
              </button>
              <button type="button" class="btn--ghost" style="margin-left: 8px" @click="newCategory.type = ''">
                取消
              </button>
            </div>
          </div>

          <div class="field">
            <label class="field__label" for="expense-asset">付款账户</label>
            <select id="expense-asset" v-model="form.assetSubjectId" :class="{ 'is-placeholder': !form.assetSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
        </template>

        <template v-else-if="tab === 'income'">
          <div class="field">
            <label class="field__label" for="income-asset">收款账户</label>
            <select id="income-asset" v-model="form.assetSubjectId" :class="{ 'is-placeholder': !form.assetSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="field">
            <div class="flex-between">
              <label class="field__label" for="income-subject">收入分类</label>
              <button type="button" class="btn--ghost btn--sm" @click="openNewCategory('INCOME')">
                + 新建
              </button>
            </div>
            <select id="income-subject" v-model="form.incomeSubjectId" :class="{ 'is-placeholder': !form.incomeSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="s in incomeLeaves" :key="s.id" :value="s.id">
                {{ s.label }}
              </option>
            </select>
          </div>

          <div v-if="newCategory.type === 'INCOME'" class="panel" style="box-shadow: var(--shadow-inset); background: var(--surface-2)">
            <div class="panel__body" style="padding: var(--sp-4)">
              <div class="field mb-0">
                <label class="field__label" for="new-inc-name">新分类名称</label>
                <input
                  id="new-inc-name"
                  v-model="newCategory.name"
                  placeholder="如：稿费、红包"
                  @keyup.enter="saveCategory"
                />
              </div>
              <div style="margin-top: 12px">
                <button type="button" :disabled="savingCategory" @click="saveCategory">
                  {{ savingCategory ? '创建中…' : '创建并使用' }}
                </button>
                <button type="button" class="btn--ghost" style="margin-left: 8px" @click="newCategory.type = ''">
                  取消
                </button>
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="field">
            <label class="field__label" for="from-account">转出账户</label>
            <select id="from-account" v-model="form.fromSubjectId" :class="{ 'is-placeholder': !form.fromSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="to-account">转入账户</label>
            <select id="to-account" v-model="form.toSubjectId" :class="{ 'is-placeholder': !form.toSubjectId }">
              <option value="" disabled>请选择</option>
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
            <div class="field__hint">
              不算消费，不影响预算
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

        <BalanceScale :lines="preview" :ledger-type="tab === 'expense' ? 'EXPENSE' : tab === 'income' ? 'INCOME' : 'TRANSFER'" />

        <div class="mt-4">
          <button type="submit" :disabled="busy">
            {{ busy ? '记账中…' : '记账' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
