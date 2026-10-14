import { defineStore } from 'pinia'
import { api } from '@/api'
import { flattenLeaves } from '@/utils/format'

/**
 * 全局状态。
 *
 * 用 Pinia 而不是之前免构建版的 reactive + provide/inject —— 依赖装上了，
 * 而且科目树/账户列表会被多个页面共享（记一笔要科目、总览要账户、账目要科目名）。
 *
 * 关于 accessToken 的存放位置：这里刻意**不**放进 store，而是放 sessionStorage。
 * 原因是 axios 拦截器在 store 之前初始化，且刷新页面时 store 会重建 ——
 * 放 sessionStorage 让 token 的生命周期与登录会话一致，避免"刷新后掉登录"。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    accounts: [],
    expenseTree: [],
    incomeTree: [],
    fullTree: [],
    loading: false,
    toast: null
  }),

  getters: {
    /** 可作为付款/收款账户的叶子科目 */
    expenseLeaves: (s) => flattenLeaves(s.expenseTree),
    incomeLeaves: (s) => flattenLeaves(s.incomeTree),

    /** 账户总余额。金额是字符串，这里显式转数字仅用于求和展示 */
    totalAssets: (s) =>
      s.accounts.reduce((sum, a) => sum + (parseFloat(a.balance) || 0), 0),

    accountName: (s) => (id) => {
      const hit = s.accounts.find((a) => a.id === Number(id))
      return hit ? hit.name : '—'
    }
  },

  actions: {
    isLoggedIn() {
      return !!sessionStorage.getItem('accessToken')
    },

    async loadSession() {
      if (!this.isLoggedIn()) return false
      try {
        const data = await api.me()
        this.user = data.user
        return true
      } catch {
        sessionStorage.removeItem('accessToken')
        sessionStorage.removeItem('refreshToken')
        return false
      }
    },

    /** 一次拉齐科目树与账户，失败时把错误抛给调用方决定怎么提示 */
    async loadSubjects() {
      const [accounts, expenseTree, incomeTree, fullTree] = await Promise.all([
        api.accounts(),
        api.subjectTree('EXPENSE'),
        api.subjectTree('INCOME'),
        api.subjectTree()
      ])
      this.accounts = accounts || []
      this.expenseTree = expenseTree || []
      this.incomeTree = incomeTree || []
      this.fullTree = fullTree || []
    },

    async login(email, password) {
      const data = await api.login({ email, password })
      this.applyTokens(data)
      this.user = data.user
      return data
    },

    async register(email, password, nickname) {
      const data = await api.register({ email, password, nickname })
      this.applyTokens(data)
      this.user = data.user
      return data
    },

    applyTokens(data) {
      sessionStorage.setItem('accessToken', data.accessToken)
      sessionStorage.setItem('refreshToken', data.refreshToken)
    },

    async logout() {
      try {
        await api.logout()
      } catch {
        // 令牌可能已失效，忽略 —— 本地状态无论如何都要清
      }
      sessionStorage.clear()
      this.user = null
      this.accounts = []
      this.expenseTree = []
      this.incomeTree = []
      this.fullTree = []
    },

    /** 统一提示。type: 'ok' | 'error' | 'info' */
    notify(type, message) {
      this.toast = { type, message }
      clearTimeout(this._toastTimer)
      this._toastTimer = setTimeout(() => {
        this.toast = null
      }, 4500)
    }
  }
})
