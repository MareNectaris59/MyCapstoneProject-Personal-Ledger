import axios from 'axios'

/**
 * HTTP 客户端。
 *
 * 与后端的两个约定（见 docs/系统设计说明书.md §6.1.4），必须严格遵守：
 *
 *   1. 业务错误返回 **HTTP 200 + 非 0 的 code**。
 *      所以判断失败不能靠 catch，必须检查 body.code。
 *      这里统一在响应拦截器里转成 reject，让调用方仍可用 try/catch，
 *      但错误对象带上了 code 与 traceId，便于排查。
 *
 *   2. 所有金额都是**字符串**（"30.00"）。
 *      前端绝不做 Number() 转换再回传，避免浮点精度丢失。
 */

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 20000
})

/** 业务错误。带上后端返回的 code 与 traceId。 */
export class ApiError extends Error {
  constructor(message, code, traceId) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

function uuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

http.interceptors.request.use((cfg) => {
  const token = sessionStorage.getItem('accessToken')
  if (token) cfg.headers.Authorization = `Bearer ${token}`

  // 写操作统一带幂等键：重复提交不会重复记账（BR-12、AC-06）
  const method = (cfg.method || 'get').toLowerCase()
  if (method !== 'get') {
    cfg.headers['Idempotency-Key'] = uuid()
  }
  return cfg
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // HTTP 200 但业务失败 —— 这是本项目最常见的"失败"形态
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(new ApiError(body.message, body.code, body.traceId))
    }
    return body ? body.data : null
  },
  (err) => {
    const resp = err.response
    if (resp?.status === 401) {
      sessionStorage.removeItem('accessToken')
      sessionStorage.removeItem('refreshToken')
      if (location.hash !== '#/login') location.hash = '#/login'
    }
    const message = resp?.data?.message || err.message || '网络请求失败'
    return Promise.reject(new ApiError(message, resp?.data?.code ?? -1, resp?.data?.traceId))
  }
)

export const api = {
  // ---- 认证 ----
  register: (payload) => http.post('/auth/register', payload),
  login: (payload) => http.post('/auth/login', payload),
  logout: () => http.post('/auth/logout', {}),
  me: () => http.get('/auth/me'),

  /**
   * 修改密码（FR-U-04）。需校验原密码。
   *
   * 成功后服务端会认为本次会话仍有效（无状态 JWT），所以前端要主动清令牌并
   * 回登录页 —— 见 SettingsView 的处理。
   */
  changePassword: (payload) => http.put('/auth/password', payload),

  /** 修改资料（FR-U-03），目前只支持昵称。返回更新后的用户信息。 */
  updateProfile: (payload) => http.put('/auth/profile', payload),
  /**
   * 注销账号（删除账号，不是退出登录）。
   * 需要密码二次确认；注销后账号永久失效，同一邮箱可重新注册。
   */
  deleteAccount: (payload) => http.post('/auth/delete', payload),

  // ---- 科目与账户 ----
  subjectTree: (type) => http.get('/subjects/tree', { params: type ? { type } : {} }),
  accounts: () => http.get('/subjects/accounts'),
  subjectBalance: (id) => http.get(`/subjects/${id}/balance`),
  subjectEntries: (id, limit = 20) =>
    http.get(`/subjects/${id}/entries`, { params: { limit } }),
  openingBalance: (id, payload) => http.post(`/subjects/${id}/opening-balance`, payload),

  /**
   * 新增科目。账户管理与记账表单里的「+ 新建分类」共用这一个接口。
   *
   * 只传 name / subjectType / accountKind —— 编码、层级、层级路径、余额方向
   * 全部由后端按规则生成。用户不需要知道这些内部概念。
   *
   * @param payload { name, subjectType: 'ASSET'|'EXPENSE'|'INCOME',
   *                  accountKind?, parentId? }
   */
  createSubject: (payload) => http.post('/subjects', payload),

  /** 启用 / 停用科目。停用后不再出现在记账下拉框，历史记录仍可查询。 */
  setSubjectEnabled: (id, enabled) =>
    http.put(`/subjects/${id}/enabled`, null, { params: { enabled } }),

  /**
   * 重命名科目。
   *
   * 只改名称，不动编码 / 层级 / 父级 —— 这些由创建时的规则决定。
   * 同一父级下不允许重名（后端会拒绝），否则记账下拉框里两项无法区分。
   */
  renameSubject: (id, name) => http.put(`/subjects/${id}`, { name }),

  /** 删除科目。存在子科目或已被账目引用时后端会拒绝（错误码 1302 / 1303）。 */
  deleteSubject: (id) => http.delete(`/subjects/${id}`),

  // ---- 记账 ----
  postExpense: (payload) => http.post('/ledgers/expense', payload),
  postIncome: (payload) => http.post('/ledgers/income', payload),
  postTransfer: (payload) => http.post('/ledgers/transfer', payload),
  ledgers: (params) => http.get('/ledgers', { params }),
  ledgerDetail: (id) => http.get(`/ledgers/${id}`),
  reverse: (id, payload) => http.post(`/ledgers/${id}/reverse`, payload),
  balanceCheck: () => http.get('/ledgers/balance-check'),

  // ---- 预算 ----
  /**
   * 预算列表。每项都带 period —— 即"今天落在哪个周期"的执行情况，
   * 因此列表页无需再逐个请求详情就能渲染进度。
   */
  budgets: () => http.get('/budgets'),

  /**
   * 新建预算。
   *
   * 只传业务字段；周期行（budget_period）由后端按 startDate 起连续生成，
   * 前端不参与周期切分 —— 周起始日、跨年周归属这些规则都在后端一处实现。
   *
   * @param payload { name, scopeType: 'TOTAL'|'SUBJECT', subjectId?,
   *                  periodType: 'WEEK'|'MONTH'|'CUSTOM', periodValue?,
   *                  startDate, endDate?, amount,
   *                  rolloverMode?: 'NONE'|'SURPLUS_ONLY'|'FULL',
   *                  alertThreshold? }
   */
  createBudget: (payload) => http.post('/budgets', payload),

  /** 预算详情（含全部周期，供趋势展示）。 */
  budgetDetail: (id) => http.get(`/budgets/${id}`),

  /** 该预算的周期列表。 */
  budgetPeriods: (id) => http.get(`/budgets/${id}/periods`),

  /** 当前周期执行情况 + 趋势 + 相关预警。 */
  budgetExecution: (id) => http.get(`/budgets/${id}/execution`),

  /**
   * 修改预算。只允许改额度 / 阈值 / 结转模式 / 启用状态 / 名称。
   * 周期结构（类型、起止日期、范围、科目）不可改 —— 改了会让已生成的周期行
   * 与新窗口定义不一致，历史执行率随之失真。
   */
  updateBudget: (id, payload) => http.put(`/budgets/${id}`, payload),

  /** 删除预算（保留历史周期行）。 */
  deleteBudget: (id) => http.delete(`/budgets/${id}`),

  /** 未读预警（跨预算）。 */
  budgetAlerts: () => http.get('/budgets/alerts'),

  /** 标记预警已读。 */
  readBudgetAlert: (id) => http.put(`/budgets/alerts/${id}/read`),

  // ---- 消费分析 ----
  /**
   * 期间总览：收入、支出、结余、净资产。
   *
   * @param params { startDate?, endDate? }（yyyy-MM-dd）都不传时后端默认本月
   */
  analysisOverview: (params) => http.get('/analysis/overview', { params }),

  /**
   * 类别占比。
   *
   * level 数的是"用户眼里的分类层级"而不是树深度：
   *   level=1 -> 餐饮 / 交通 / 居住
   *   level=2 -> 午餐 / 打车
   * （科目树里"费用"是类型容器，不作为分类返回）
   */
  analysisCategoryShare: (params) => http.get('/analysis/category-share', { params }),

  /**
   * 消费趋势。
   *
   * 只返回有数据的周期，缺失月份不会补 0 —— 补 0 会让折线出现
   * "某月突然归零"的假象。若图表需要连续横轴，由前端自行补点。
   */
  analysisTrend: (params) => http.get('/analysis/trend', { params }),

  /**
   * 同比 + 环比。
   *
   * rate 为 null 表示"基期无数据"，此时 rateAvailable=false，
   * 前端应展示"—"并给出 note，而不是 0% 或 ∞。
   */
  analysisCompare: (params) => http.get('/analysis/compare', { params }),

  /** 类别贡献度：谁造成了增减。正负分列，用于双向条形图。 */
  analysisContribution: (params) => http.get('/analysis/contribution', { params }),

  /**
   * 下载分析结果 CSV。
   *
   * **刻意不走上面的 `http` 实例**：它的响应拦截器会按 `{code, message, data}`
   * 解包，而这里后端返回的是裸 CSV（文件下载无法塞进统一响应体）。
   * 因此用裸 axios + responseType 'blob'，并自己补 Authorization 头。
   *
   * @returns {Promise<Blob>} 调用方用 URL.createObjectURL 触发下载
   */
  downloadAnalysisCsv: async (params) => {
    const token = sessionStorage.getItem('accessToken')
    const resp = await axios.get('/api/v1/analysis/export', {
      params,
      responseType: 'blob',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      timeout: 30000
    })
    return resp.data
  }
}

export default http
