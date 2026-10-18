<script setup>
/**
 * 消费分析。
 *
 * 四个图表 + 一组统计卡，回答"钱花在哪儿、比上期多了还是少了"。
 *
 * ECharts 按需引入而不是 `import * as echarts from 'echarts'`：
 * 后者会把地图、3D、甘特等全部打进产物（约 1MB），而这里只用到
 * 折线、饼、柱三种。按需引入后只多约 300KB。
 *
 * 三处来自后端口径的渲染约束（写错会导致图表"看着对但其实是错的"）：
 *   1. rate 为 null = "基期无数据"，必须显示「—」，不能画成 0%
 *      （后端刻意不返回 100% 也不返回 ∞，见设计文档 §7.6.3）
 *   2. 趋势只含有数据的周期，缺失月份不补 0。横轴若要求连续，
 *      由前端自己补 0 点并如实标注，而不是让折线凭空断掉
 *   3. 贡献度是双向的：diff < 0 的科目是"帮忙省了钱"，
 *      条形图必须正负分列，不能全画成正数
 */
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent, TitleComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, today, currentMonth } from '@/utils/format'

echarts.use([
  LineChart, PieChart, BarChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  CanvasRenderer
])

const auth = useAuthStore()

/* ---------------- 期间选择 ---------------- */
const PRESETS = [
  { key: 'thisMonth', label: '本月' },
  { key: 'lastMonth', label: '上月' },
  { key: 'last3', label: '近 3 个月' },
  { key: 'thisYear', label: '今年' }
]

const preset = ref('thisMonth')

function rangeOf(key) {
  const now = new Date()
  const p = (n) => String(n).padStart(2, '0')
  const y = now.getFullYear()
  const m = now.getMonth()
  const firstOf = (yy, mm) => `${yy}-${p(mm + 1)}-01`
  const lastOf = (yy, mm) => new Date(yy, mm + 1, 0).getDate()

  switch (key) {
    case 'lastMonth': {
      const py = m === 0 ? y - 1 : y
      const pm = m === 0 ? 11 : m - 1
      return { startDate: firstOf(py, pm), endDate: `${py}-${p(pm + 1)}-${lastOf(py, pm)}` }
    }
    case 'last3': {
      const sm = m - 2
      const sy = sm < 0 ? y - 1 : y
      const smm = sm < 0 ? sm + 12 : sm
      return { startDate: firstOf(sy, smm), endDate: today() }
    }
    case 'thisYear':
      return { startDate: `${y}-01-01`, endDate: today() }
    default:
      return { startDate: firstOf(y, m), endDate: today() }
  }
}

/* ---------------- 数据 ---------------- */
const overview = ref(null)
const shares = ref([])
const trendPoints = ref([])
const compare = ref(null)
const contribution = ref(null)
const loading = ref(false)
const chartError = ref('')

const range = computed(() => rangeOf(preset.value))

function notifyRange(p) {
  auth.notify('info', `已切换到「${p.label}」`)
}

async function load() {
  loading.value = true
  chartError.value = ''
  const { startDate, endDate } = range.value
  try {
    const [ov, share, trend, cmp, contrib] = await Promise.all([
      api.analysisOverview({ startDate, endDate }),
      api.analysisCategoryShare({ startDate, endDate, level: 1 }),
      api.analysisTrend({
        granularity: 'MONTH',
        startDate: rangeOf('thisYear').startDate,
        endDate
      }),
      api.analysisCompare({ granularity: 'MONTH', period: currentMonth(), alignWindow: true }),
      api.analysisContribution({ granularity: 'MONTH', period: currentMonth() })
    ])
    overview.value = ov
    shares.value = share.items || []
    trendPoints.value = trend.points || []
    compare.value = cmp
    contribution.value = contrib
    await nextTick()
    renderAll()
  } catch (e) {
    chartError.value = e.message
  } finally {
    loading.value = false
  }
}

/* ---------------- 图表 ---------------- */
const pieRef = ref(null)
const lineRef = ref(null)
const barRef = ref(null)
const contribRef = ref(null)
let pieChart = null
let lineChart = null
let barChart = null
let contribChart = null

const AXIS = '#64748b'
const GRID_LINE = 'rgba(15,23,42,0.08)'
const SERIES_COLORS = ['#1e40af', '#059669', '#d97706', '#dc2626', '#7c3aed', '#0891b2', '#be185d', '#65a30d']

function baseOption() {
  return {
    textStyle: { fontFamily: "'Fira Sans','Microsoft YaHei',sans-serif" },
    tooltip: { trigger: 'item' }
  }
}

function renderPie() {
  if (!pieRef.value) return
  pieChart = pieChart || echarts.init(pieRef.value)
  const data = shares.value.map((s) => ({ name: s.subjectName, value: parseFloat(s.amount) }))
  pieChart.setOption({
    ...baseOption(),
    color: SERIES_COLORS,
    tooltip: {
      trigger: 'item',
      formatter: (p) => `${p.name}<br/>${formatMoney(p.value)} (${p.percent}%)`
    },
    legend: { bottom: 0, textStyle: { color: AXIS, fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['46%', '70%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 6 },
      label: { show: false },
      data
    }]
  }, true)
}

function renderLine() {
  if (!lineRef.value) return
  lineChart = lineChart || echarts.init(lineRef.value)
  const points = trendPoints.value
  lineChart.setOption({
    ...baseOption(),
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => `${ps[0].axisValue}<br/>${formatMoney(ps[0].value)}`
    },
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.period),
      axisLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: AXIS, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: AXIS, fontSize: 11 }
    },
    series: [{
      type: 'line',
      smooth: true,
      symbolSize: 7,
      data: points.map((p) => parseFloat(p.amount)),
      lineStyle: { width: 3, color: '#1e40af' },
      itemStyle: { color: '#1e40af' },
      areaStyle: { color: 'rgba(30,64,175,0.10)' }
    }]
  }, true)
}

function renderBar() {
  if (!barRef.value) return
  barChart = barChart || echarts.init(barRef.value)
  const items = (contribution.value?.contributors) || []
  barChart.setOption({
    ...baseOption(),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps) => {
        const p = ps[0]
        return `${p.name}<br/>变化 ${formatMoney(p.value)}`
      }
    },
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: AXIS, fontSize: 11 }
    },
    yAxis: {
      type: 'category',
      // 反向排列，使金额最大的显示在最上方
      data: items.map((c) => c.subjectName).reverse(),
      axisLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: AXIS, fontSize: 11 }
    },
    series: [{
      type: 'bar',
      data: items.map((c) => {
        const v = parseFloat(c.diff)
        // 涨用红、降用绿：这里表达的是"支出变化方向"，与损益无关
        return { value: v, itemStyle: { color: v >= 0 ? '#dc2626' : '#059669' } }
      }).reverse(),
      barMaxWidth: 20,
      itemStyle: { borderRadius: [0, 4, 4, 0] }
    }]
  }, true)
}

function renderCompare() {
  if (!contribRef.value) return
  contribChart = contribChart || echarts.init(contribRef.value)
  const c = compare.value
  if (!c) return
  const rows = [
    { name: '本期', value: parseFloat(c.currentAmount), color: '#1e40af' },
    { name: `环比 ${c.mom.period}`, value: parseFloat(c.mom.amount), color: '#059669' },
    { name: `同比 ${c.yoy.period}`, value: parseFloat(c.yoy.amount), color: '#d97706' }
  ]
  contribChart.setOption({
    ...baseOption(),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps) => `${ps[0].name}<br/>${formatMoney(ps[0].value)}`
    },
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: rows.map((r) => r.name), axisLabel: { color: AXIS, fontSize: 11 } },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: GRID_LINE } },
      axisLabel: { color: AXIS, fontSize: 11 }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 44,
      data: rows.map((r) => ({ value: r.value, itemStyle: { color: r.color } })),
      itemStyle: { borderRadius: [6, 6, 0, 0] }
    }]
  }, true)
}

function renderAll() {
  renderPie()
  renderLine()
  renderBar()
  renderCompare()
}

function onResize() {
  pieChart?.resize()
  lineChart?.resize()
  barChart?.resize()
  contribChart?.resize()
}

/* ---------------- 展示辅助 ---------------- */

/** 比率显示：null 表示"基期无数据"，显示「—」而不是 0% */
function rateText(rate) {
  return rate === null || rate === undefined ? '—' : `${rate}%`
}

function rateNote(base) {
  return base && !base.rateAvailable && base.note ? base.note : ''
}

/* ---------------- 导出 ---------------- */

const exporting = ref(false)

/**
 * 导出当前期间的 CSV。
 *
 * 后端返回裸 CSV（带 UTF-8 BOM，Excel 直接打开中文不乱码），
 * 所以用 Blob + 临时 <a> 触发下载，而不是常规的 JSON 请求。
 */
async function exportCsv(type) {
  exporting.value = true
  try {
    const blob = await api.downloadAnalysisCsv({
      type,
      granularity: 'MONTH',
      startDate: range.value.startDate,
      endDate: range.value.endDate
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = type === 'category' ? '支出分类汇总.csv' : '支出趋势.csv'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    // 必须撤销，否则这个 Blob 会一直占着内存直到页面关闭
    URL.revokeObjectURL(url)
    auth.notify('ok', '已导出')
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    exporting.value = false
  }
}

watch(preset, (n, o) => {
  if (o) notifyRange(PRESETS.find((p) => p.key === n) || { label: n })
  load()
})

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  pieChart?.dispose()
  lineChart?.dispose()
  barChart?.dispose()
  contribChart?.dispose()
})
</script>

<template>
  <div class="page-head">
    <h2>消费分析</h2>
    <div class="seg">
      <button
        v-for="p in PRESETS"
        :key="p.key"
        class="btn--ghost btn--sm"
        :class="{ 'seg--on': preset === p.key }"
        @click="preset = p.key"
      >
        {{ p.label }}
      </button>
    </div>
    <div class="export-group">
      <button class="btn--ghost btn--sm" :disabled="exporting" @click="exportCsv('category')">
        {{ exporting ? '导出中…' : '导出分类 CSV' }}
      </button>
      <button class="btn--ghost btn--sm" :disabled="exporting" @click="exportCsv('trend')">
        导出趋势 CSV
      </button>
    </div>
  </div>

  <div v-if="chartError" class="alert alert--error">{{ chartError }}</div>

  <!-- 统计卡 -->
  <div class="grid grid--3" style="margin-bottom: 16px">
    <div class="stat">
      <div class="stat__label">支出</div>
      <div class="stat__value stat__value--neg">{{ formatMoney(overview?.expense) }}</div>
    </div>
    <div class="stat">
      <div class="stat__label">收入</div>
      <div class="stat__value stat__value--pos">{{ formatMoney(overview?.income) }}</div>
    </div>
    <div class="stat">
      <div class="stat__label">结余</div>
      <div class="stat__value" :class="parseFloat(overview?.balance || 0) < 0 ? 'stat__value--neg' : 'stat__value--pos'">
        {{ formatMoney(overview?.balance) }}
      </div>
    </div>
  </div>

  <!-- 同环比 -->
  <div class="panel">
    <div class="panel__head">和以前比</div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="compare" class="grid grid--2">
        <div>
          <div class="stat__label">环比（对比 {{ compare.mom.period }}）</div>
          <div class="stat__value" style="font-size: var(--fs-xl)">
            {{ parseFloat(compare.mom.diff) >= 0 ? '+' : '' }}{{ formatMoney(compare.mom.diff) }}
          </div>
          <!-- rate 为 null 时不显示 0%，而是「—」加原因 -->
          <div class="field__hint">
            变化 {{ rateText(compare.mom.rate) }}
            <template v-if="rateNote(compare.mom)"> · {{ rateNote(compare.mom) }}</template>
          </div>
        </div>
        <div>
          <div class="stat__label">同比（对比 {{ compare.yoy.period }}）</div>
          <div class="stat__value" style="font-size: var(--fs-xl)">
            {{ parseFloat(compare.yoy.diff) >= 0 ? '+' : '' }}{{ formatMoney(compare.yoy.diff) }}
          </div>
          <div class="field__hint">
            变化 {{ rateText(compare.yoy.rate) }}
            <template v-if="rateNote(compare.yoy)"> · {{ rateNote(compare.yoy) }}</template>
          </div>
        </div>
      </div>
      <div v-if="compare && !compare.windowAligned && compare.alignNote" class="alert alert--info" style="margin-top: 12px">
        {{ compare.alignNote }}
      </div>
      <div ref="contribRef" style="height: 200px; margin-top: 12px"></div>
    </div>
  </div>

  <!-- 花费构成 -->
  <div class="panel">
    <div class="panel__head">钱花在哪儿了</div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="!shares.length" class="empty">这段时间还没有支出记录</div>
      <div v-else ref="pieRef" style="height: 280px"></div>
    </div>
  </div>

  <!-- 趋势 -->
  <div class="panel">
    <div class="panel__head">
      <span>支出趋势</span>
      <span class="faint" style="font-size: 12px">今年逐月</span>
    </div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="!trendPoints.length" class="empty">今年还没有支出记录</div>
      <div v-else ref="lineRef" style="height: 260px"></div>
    </div>
  </div>

  <!-- 增减贡献 -->
  <div class="panel">
    <div class="panel__head">
      <span>谁让支出变了</span>
      <span v-if="contribution" class="faint" style="font-size: 12px">
        {{ contribution.currentPeriod }} 对比 {{ contribution.basePeriod }}，
        合计 {{ parseFloat(contribution.totalDiff) >= 0 ? '+' : '' }}{{ formatMoney(contribution.totalDiff) }}
      </span>
    </div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="!contribution || !contribution.contributors.length" class="empty">暂无可比较的数据</div>
      <template v-else>
        <div ref="barRef" :style="{ height: Math.max(140, contribution.contributors.length * 34) + 'px' }"></div>
        <div class="field__hint" style="margin-top: 8px">
          红色表示这项花得比上期多，绿色表示比上期少
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.seg { display: flex; gap: 4px; }
.seg--on {
  background: var(--primary) !important;
  color: var(--on-primary) !important;
}
.export-group { display: flex; gap: 4px; margin-left: auto; }
</style>
