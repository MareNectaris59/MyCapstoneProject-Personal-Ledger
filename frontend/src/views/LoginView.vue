<script setup>
/**
 * 登录 / 注册。
 *
 * 注册后后端会自动初始化整套 39 个会计科目（FR-A-01），
 * 所以新用户无需任何配置即可开始记账 —— 这一点在界面上明确告知，
 * 否则用户会疑惑"账户从哪来"。
 */
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref('login')
const busy = ref(false)
const error = ref('')

const form = reactive({
  email: '',
  password: '',
  nickname: ''
})

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}

async function submit() {
  error.value = ''

  if (!form.email || !form.password) {
    error.value = '请填写邮箱和密码'
    return
  }
  if (mode.value === 'register' && form.password.length < 8) {
    error.value = '密码至少需要 8 位'
    return
  }

  busy.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(form.email, form.password)
    } else {
      await auth.register(form.email, form.password, form.nickname)
    }

    // 登录成功后才拉科目树 —— 失败不会阻塞进入首页，由守卫兜底
    try {
      await auth.loadSubjects()
    } catch (e) {
      auth.notify('error', `科目加载失败：${e.message}`)
    }

    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' ? redirect : '/')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="auth__box">
      <h1 class="auth__title">个人复式记账</h1>
      <div class="auth__sub">借贷平衡 · 多账户 · 消费分析</div>

      <div v-if="error" class="alert alert--error">{{ error }}</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label class="field__label" for="email">邮箱</label>
          <input
            id="email"
            v-model.trim="form.email"
            type="email"
            autocomplete="username"
            placeholder="you@example.com"
          />
        </div>

        <div v-if="mode === 'register'" class="field">
          <label class="field__label" for="nickname">昵称</label>
          <input id="nickname" v-model.trim="form.nickname" placeholder="可留空" />
        </div>

        <div class="field">
          <label class="field__label" for="password">密码</label>
          <input
            id="password"
            v-model="form.password"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            placeholder="至少 8 位"
          />
        </div>

        <button type="submit" class="btn--block" :disabled="busy">
          {{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册并开始记账' }}
        </button>
      </form>

      <div class="auth__switch">
        <a @click="toggleMode">
          {{ mode === 'login' ? '还没有账号？去注册' : '已有账号？去登录' }}
        </a>
      </div>
    </div>
  </div>
</template>
