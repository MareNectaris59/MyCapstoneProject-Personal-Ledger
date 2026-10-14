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

  // ---- 记账 ----
  postExpense: (payload) => http.post('/ledgers/expense', payload),
  postIncome: (payload) => http.post('/ledgers/income', payload),
  postTransfer: (payload) => http.post('/ledgers/transfer', payload),
  ledgers: (params) => http.get('/ledgers', { params }),
  ledgerDetail: (id) => http.get(`/ledgers/${id}`),
  reverse: (id, payload) => http.post(`/ledgers/${id}/reverse`, payload),
  balanceCheck: () => http.get('/ledgers/balance-check')
}

export default http
