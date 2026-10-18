import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由。
 *
 * 用 hash 模式（#/ledgers）而不是 history 模式，理由很实际：
 * 后端只托管静态资源，没有为 SPA 配置 fallback 到 index.html 的规则。
 * history 模式下用户刷新 /ledgers 会得到 404。用 hash 模式则完全不需要
 * 服务端配合，部署到 Nginx 时也不用额外写 try_files。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '总览' }
  },
  {
    path: '/record',
    name: 'record',
    component: () => import('@/views/RecordView.vue'),
    meta: { title: '记一笔' }
  },
  {
    path: '/ledgers',
    name: 'ledgers',
    component: () => import('@/views/LedgerView.vue'),
    meta: { title: '账目' }
  },
  {
    path: '/subjects/:id(\\d+)',
    name: 'subject-detail',
    component: () => import('@/views/SubjectDetailView.vue'),
    meta: { title: '科目明细' }
  },
  {
    path: '/categories',
    name: 'categories',
    component: () => import('@/views/CategoryView.vue'),
    meta: { title: '支出分类' }
  },
  {
    path: '/budgets',
    name: 'budgets',
    component: () => import('@/views/BudgetView.vue'),
    meta: { title: '预算' }
  },
  {
    path: '/analysis',
    name: 'analysis',
    component: () => import('@/views/AnalysisView.vue'),
    meta: { title: '消费分析' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { title: '账号设置' }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/**
 * 权限守卫。
 *
 * 只做"有没有令牌"的判断，不做角色校验 —— 本系统没有角色模型。
 * 首次进入受保护页面时拉一次会话与科目数据，避免每个页面各自加载。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录还去登录页 → 送回首页
    return auth.isLoggedIn() ? { name: 'dashboard' } : true
  }

  if (!auth.isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!auth.user) {
    const ok = await auth.loadSession()
    if (!ok) return { name: 'login' }

    // 科目与账户是全站共享数据，登录后一次拉齐
    try {
      await auth.loadSubjects()
    } catch (e) {
      auth.notify('error', `加载科目失败：${e.message}`)
    }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · 个人复式记账`
    : '个人复式记账与消费分析系统'
})

export default router
