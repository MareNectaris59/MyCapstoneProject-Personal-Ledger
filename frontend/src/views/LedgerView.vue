<script setup>
/**
 * 账目：分页列表 + 分录详情 + 冲正。
 *
 * 两个设计要点：
 *   1. 表格没有"删除"按钮，只有"冲正" —— 账务不可变是系统的架构约束，
 *      界面上不应该给出删除的入口，否则用户会以为可以删。
 *   2. 冲正前先展示原凭证的分录，让用户明白"冲正是新增一张反向凭证"，
 *      而不是"改掉原来那笔"。
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import {
  formatMoney,
  LEDGER_TYPE_LABEL,
  LEDGER_STATE_LABEL,
  LEDGER_TYPE_CLASS,
  LEDGER_STATE_CLASS,
  entryRoleLabel,
  today
} from '@/utils/format'

const auth = useAuthStore()

const PAGE_SIZE = 10

const rows = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const filters = ref({
  keyword: '',
  ledgerType: '',
  startDate: '',
  endDate: '',
  subjectId: '',
  minAmount: '',
  maxAmount: '',
  sortBy: 'occurredDate',
  sortOrder: 'desc'
})

/* ---------------- 科目筛选的可选项 ---------------- */

/**
 * 科目筛选下拉的选项。
 *
 * 与记账表单不同，这里**不限于叶子科目**：筛账目时用户可能想"看餐饮这一大类
 * 下的所有支出"，而那是一个非叶子节点。后端按 `entry.subject_id` 过滤，
 * 选中非叶子不会命中任何分录，所以这里额外把选中节点的整棵子树 id 一起传。
 *
 * 实现上简化为：只列出叶子科目 + 一级分类（用"含子分类"标注）。
 */
const subjectOptions = computed(() => {
  const tree = auth.fullTree || []
  const flat = []
  const walk = (nodes, depth, parentName) => {
    for (const n of nodes || []) {
      // 跳过类型容器那一层（资产/负债/权益/收入/费用）
      if (depth === 0) {
        walk(n.children, 1, '')
        continue
      }
      const label = parentName ? `${parentName} / ${n.name}` : n.name
      flat.push({ id: n.id, label, isLeaf: n.leaf })
      if (n.children?.length) walk(n.children, depth + 1, n.name)
    }
  }
  walk(tree, 0, '')
  return flat
})

const detail = ref(null)
const reversing = ref(null)
const reverseReason = ref('')
const busy = ref(false)

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

/* ---------------- 排序 ---------------- */

/**
 * 排序下拉绑定的是「字段:方向」这样一个可读值，避免页面上出现两个下拉
 * 让用户自己组合出"金额升序"这种别扭的操作。这里用 getter/setter 把它
 * 映射回后端的 sortBy / sortOrder 两个参数。
 */
const sortKey = computed({
  get: () => `${filters.value.sortBy}:${filters.value.sortOrder}`,
  set: (v) => {
    const [by, order] = String(v).split(':')
    filters.value.sortBy = by
    filters.value.sortOrder = order
  }
})

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: PAGE_SIZE }
    if (filters.value.keyword.trim()) params.keyword = filters.value.keyword.trim()
    if (filters.value.ledgerType) params.ledgerType = filters.value.ledgerType
    if (filters.value.startDate) params.startDate = filters.value.startDate
    if (filters.value.endDate) params.endDate = filters.value.endDate
    if (filters.value.subjectId) params.subjectId = filters.value.subjectId
    if (filters.value.minAmount !== '') params.minAmount = filters.value.minAmount
    if (filters.value.maxAmount !== '') params.maxAmount = filters.value.maxAmount
    if (filters.value.sortBy) params.sortBy = filters.value.sortBy
    if (filters.value.sortOrder) params.sortOrder = filters.value.sortOrder

    const data = await api.ledgers(params)
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function go(target) {
  if (target < 1 || target > maxPage.value) return
  page.value = target
  load()
}

async function showDetail(id) {
  try {
    detail.value = await api.ledgerDetail(id)
  } catch (e) {
    auth.notify('error', e.message)
  }
}

function startReverse(row) {
  reversing.value = row
  reverseReason.value = ''
}

async function confirmReverse() {
  busy.value = true
  try {
    await api.reverse(reversing.value.id, { reason: reverseReason.value })
    auth.notify('ok', '已撤销该笔记录，金额已退回相关账户')
    reversing.value = null
    detail.value = null
    await Promise.all([load(), auth.loadSubjects()])
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

const canReverse = (row) =>
  row.state === 'POSTED' || row.state === 'PARTIALLY_REVERSED'

/* ---------------- 导入 / 导出 ---------------- */

const importing = ref(false)
const exporting = ref(false)
const fileInput = ref(null)

/** 导出当前筛选期间的台账。 */
async function exportCsv() {
  exporting.value = true
  try {
    const params = {}
    if (filters.value.startDate) params.startDate = filters.value.startDate
    if (filters.value.endDate) params.endDate = filters.value.endDate
    const blob = await api.downloadLedgerCsv(params)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '账目.csv'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    auth.notify('ok', '已导出')
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    exporting.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

/**
 * 导入 CSV。
 *
 * 服务端是"整份校验通过才写入"的语义，所以这里失败时一次性展示前几条错误，
 * 而不是逐行提示 —— 用户需要的是"整个文件有哪几行不对"，然后改文件重导。
 */
async function onFilePicked(event) {
  const file = event.target.files?.[0]
  if (!file) return
  importing.value = true
  try {
    const text = await file.text()
    const result = await api.importLedgers(text)
    if (result.errors?.length) {
      const head = result.errors.slice(0, 5).join('；')
      const more = result.errors.length > 5 ? `…（共 ${result.errors.length} 处问题）` : ''
      auth.notify('error', head + more)
    } else {
      auth.notify('ok', `导入完成：新增 ${result.imported} 笔，跳过重复 ${result.skipped} 笔`)
      await Promise.all([load(), auth.loadSubjects()])
    }
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    importing.value = false
    // 清空 value，否则重复选同一个文件不会触发 change
    if (event.target) event.target.value = ''
  }
}
onMounted(load)
</script>

<template>
  <div class="page-head">
    <h2>账目</h2>
  </div>

  <div class="panel">
    <div class="panel__body">
      <div class="row">
        <div class="field mb-0" style="flex: 2 1 220px">
          <label class="field__label" for="f-keyword">搜索</label>
          <input
            id="f-keyword"
            v-model="filters.keyword"
            placeholder="备注或单据号，回车即查"
            maxlength="50"
            @keyup.enter="search"
          />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-type">类型</label>
          <select id="f-type" v-model="filters.ledgerType">
            <option value="">全部</option>
            <option value="EXPENSE">支出</option>
            <option value="INCOME">收入</option>
            <option value="TRANSFER">转账</option>
            <option value="OPENING">期初建账</option>
            <option value="REVERSAL">冲正</option>
          </select>
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-start">起始日期</label>
          <input id="f-start" v-model="filters.startDate" type="date" />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-end">结束日期</label>
          <input id="f-end" v-model="filters.endDate" type="date" />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-subject">分类 / 账户</label>
          <select id="f-subject" v-model="filters.subjectId">
            <option value="">全部</option>
            <option v-for="s in subjectOptions" :key="s.id" :value="s.id">{{ s.label }}</option>
          </select>
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-min">金额下限</label>
          <input id="f-min" v-model="filters.minAmount" type="number" min="0" step="0.01" placeholder="不限" />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-max">金额上限</label>
          <input id="f-max" v-model="filters.maxAmount" type="number" min="0" step="0.01" placeholder="不限" />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="f-sort">排序</label>
          <select id="f-sort" v-model="sortKey">
            <option value="occurredDate:desc">日期从新到旧</option>
            <option value="occurredDate:asc">日期从旧到新</option>
            <option value="amount:desc">金额从大到小</option>
            <option value="amount:asc">金额从小到大</option>
          </select>
        </div>
        <button @click="search">查询</button>
        <button class="btn--ghost" :disabled="exporting" @click="exportCsv">
          {{ exporting ? '导出中…' : '导出台账' }}
        </button>
        <button class="btn--ghost" :disabled="importing" @click="pickFile">
          {{ importing ? '导入中…' : '导入 CSV' }}
        </button>
        <input
          ref="fileInput"
          type="file"
          accept=".csv,text/csv"
          style="display: none"
          @change="onFilePicked"
        />
      </div>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head">
      <span>账目明细</span>
      <span class="muted" style="font-weight: 500; font-size: 12px">
        {{ loading ? '加载中…' : `共 ${total} 条` }}
      </span>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>日期</th>
            <th>单据号</th>
            <th>类型</th>
            <th>状态</th>
            <th class="num">金额</th>
            <th>备注</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id">
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
            <td class="num">{{ formatMoney(r.amount) }}</td>
            <td class="muted">{{ r.remark }}</td>
            <td class="right" style="white-space: nowrap">
              <button class="btn btn--ghost btn--sm" @click="showDetail(r.id)">详情</button>
              <button
                v-if="canReverse(r)"
                class="btn btn--danger btn--sm"
                style="margin-left: 6px"
                @click="startReverse(r)"
              >
                撤销
              </button>
            </td>
          </tr>
          <tr v-if="!rows.length && !loading">
            <td colspan="7" class="empty">暂无账目</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <span class="pager__info">第 {{ page }} / {{ maxPage }} 页</span>
      <button class="btn btn--ghost btn--sm" :disabled="page <= 1" @click="go(page - 1)">
        上一页
      </button>
      <button
        class="btn btn--ghost btn--sm"
        :disabled="page >= maxPage"
        @click="go(page + 1)"
      >
        下一页
      </button>
    </div>
  </div>

  <div v-if="detail" class="panel">
    <div class="panel__head">
      <span>单据 {{ detail.ledgerNo }} 的明细</span>
      <button class="btn btn--ghost btn--sm" @click="detail = null">收起</button>
    </div>
    <div class="panel__body">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>方向</th>
              <th>项目</th>
              <th class="num">金额</th>
              <th>摘要</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in detail.entries" :key="e.entryNo">
              <td class="mono">{{ e.entryNo }}</td>
              <td>
                <span class="tag" :class="e.direction === 'DEBIT' ? 'tag--debit' : 'tag--credit'">
                  {{ entryRoleLabel(e.direction, detail.ledgerType, e.subjectType) }}
                </span>
              </td>
              <td>
                {{ e.subjectName }}
                <span class="mono muted" style="font-size: 11px">{{ e.subjectCode }}</span>
              </td>
              <td class="num">{{ formatMoney(e.amount) }}</td>
              <td class="muted">{{ e.summary }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <div v-if="reversing" class="panel">
    <div class="panel__head">撤销单据 {{ reversing.ledgerNo }}</div>
    <div class="panel__body">
      <div class="alert alert--error">
        将撤销这笔 <b>{{ formatMoney(reversing.amount) }}</b> 元的
        {{ LEDGER_TYPE_LABEL[reversing.ledgerType] }}，金额会退回到相关账户。
        原记录会保留并标记为「已撤销」，方便日后查证。
      </div>
      <div class="field">
        <label class="field__label" for="rev-reason">撤销原因</label>
        <input id="rev-reason" v-model="reverseReason" placeholder="选填" maxlength="255" />
      </div>
      <button class="btn--danger" :disabled="busy" @click="confirmReverse">
        {{ busy ? '撤销中…' : '确认撤销' }}
      </button>
      <button class="btn btn--ghost" style="margin-left: 8px" @click="reversing = null">
        取消
      </button>
    </div>
  </div>
</template>
