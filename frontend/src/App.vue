<script setup>
/**
 * 应用外壳。
 *
 * 布局分两种状态：
 *   - 未登录 → 直接渲染路由（只有登录页会匹配到）
 *   - 已登录 → 侧栏 + 主区
 *
 * 用路由的 meta.public 判断而不是 auth.user，原因是刷新页面的瞬间 user 还没拉回来，
 * 用 user 判断会让侧栏闪一下再消失。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppToast from '@/components/AppToast.vue'

const route = useRoute()
const auth = useAuthStore()

const isPublic = computed(() => !!route.meta.public)

const navItems = [
  { to: '/', label: '总览' },
  { to: '/record', label: '记一笔' },
  { to: '/ledgers', label: '账目' },
  { to: '/settings', label: '账号设置' }
]

async function onLogout() {
  await auth.logout()
  location.hash = '#/login'
}
</script>

<template>
  <AppToast />

  <div v-if="isPublic" class="auth-host">
    <RouterView />
  </div>

  <div v-else class="layout">
    <nav class="sidebar">
      <div class="sidebar__brand">复式记账</div>
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="sidebar__link"
      >
        {{ item.label }}
      </RouterLink>

      <div class="sidebar__who">
        <div>{{ auth.user?.nickname || '—' }}</div>
        <div class="mono" style="font-size: 11px">{{ auth.user?.email }}</div>
        <button class="btn btn--ghost btn--sm" @click="onLogout">退出登录</button>
      </div>
    </nav>

    <main class="main">
      <div class="main__inner">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-host {
  min-height: 100vh;
}
</style>
