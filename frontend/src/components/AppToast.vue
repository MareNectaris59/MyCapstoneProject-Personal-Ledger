<script setup>
/**
 * 顶部横幅式通知。
 *
 * 用整条横幅而不是右上角小提示：Brutalism 的语言是"大块、强对比"，
 * 小圆角提示条与整体风格不搭。
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
.toast-enter-active,
.toast-leave-active {
  transition: transform 120ms ease-out, opacity 120ms ease-out;
}
.toast-enter-from,
.toast-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
