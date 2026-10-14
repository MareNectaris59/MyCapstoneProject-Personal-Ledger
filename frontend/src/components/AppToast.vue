<script setup>
/**
 * 浮起的黏土胶囊通知（右下角）。
 *
 * 上一版 Brutalism 用的是顶部整条横幅 —— 那种强入侵感与柔和风格冲突，
 * 改为右下角浮起的小胶囊，配 theme.css 的 --clay-lg 阴影。
 */
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const { toast } = storeToRefs(auth)

const cls = (type) => ({
  ok: 'toast--ok',
  error: 'toast--error',
  info: 'toast--info'
}[type] || 'toast--info')
</script>

<template>
  <Transition name="toast">
    <div v-if="toast" :class="['toast', cls(toast.type)]" role="status" aria-live="polite">
      {{ toast.message }}
    </div>
  </Transition>
</template>

<style scoped>
.toast-enter-active {
  transition: transform var(--t-base) var(--ease-bounce), opacity var(--t-fast);
}
.toast-leave-active {
  transition: transform var(--t-fast) ease-in, opacity var(--t-fast) ease-in;
}
.toast-enter-from,
.toast-leave-to {
  transform: translateY(12px) scale(0.96);
  opacity: 0;
}
</style>
