<script setup>
/**
 * 登录 / 注册 —— 左图右表单的分栏布局。
 *
 * 左侧放壁纸与产品定位，右侧是一块白色面板承载表单。
 * 这样做的原因不是"好看"，是**表单区域需要高对比度**：
 * 白底 + 深蓝字才能保证输入框、报错、按钮都清晰可读；
 * 而壁纸本身偏中蓝，直接在上面放表单会牺牲可读性。
 *
 * 注册后后端会自动初始化整套会计科目（FR-A-01），
 * 所以新用户无需任何配置即可开始记账 —— 这一点在注册态明确告知，
 * 否则用户会疑惑"账户从哪来"。
 */
import { ref, reactive, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref('login')
const busy = ref(false)
const error = ref('')
const showPassword = ref(false)

const form = reactive({
  email: '',
  password: '',
  nickname: ''
})

const isLogin = computed(() => mode.value === 'login')

const title = computed(() => (isLogin.value ? '登录' : '注册'))
const subtitle = computed(() =>
  isLogin.value ? '用邮箱和密码进入' : '注册后会自动建好整套科目，可以直接记账'
)

function toggleMode() {
  mode.value = isLogin.value ? 'register' : 'login'
  error.value = ''
  showPassword.value = false
}

async function submit() {
  error.value = ''

  if (!form.email || !form.password) {
    error.value = '请填写邮箱和密码'
    return
  }
  if (!isLogin.value && form.password.length < 8) {
    error.value = '密码至少需要 8 位'
    return
  }

  busy.value = true
  try {
    if (isLogin.value) {
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
    <!-- 左侧：壁纸 + 产品定位 -->
    <section class="auth__art">
      <div class="auth__artInner">
        <div class="auth__brand">个人复式记账</div>
        <p class="auth__tagline">借贷平衡 · 多账户 · 消费分析</p>
      </div>
    </section>

    <!-- 右侧：表单面板 -->
    <section class="auth__panel">
      <div class="auth__form">
        <h1 class="auth__title">{{ title }}</h1>
        <p class="auth__sub">{{ subtitle }}</p>

        <div v-if="error" class="alert alert--error">{{ error }}</div>

        <form @submit.prevent="submit">
          <div class="field">
            <label class="field__label" for="email">邮箱</label>
            <div class="auth__input">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <path d="m3.5 7 8.5 5.5L20.5 7" />
              </svg>
              <input
                id="email"
                v-model.trim="form.email"
                type="email"
                autocomplete="username"
                placeholder="you@example.com"
              />
            </div>
          </div>

          <div v-if="!isLogin" class="field">
            <label class="field__label" for="nickname">昵称</label>
            <div class="auth__input">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="8.5" r="3.5" />
                <path d="M5 20c0-3.4 3.1-5.8 7-5.8s7 2.4 7 5.8" />
              </svg>
              <input id="nickname" v-model.trim="form.nickname" placeholder="可留空" />
            </div>
          </div>

          <div class="field">
            <label class="field__label" for="password">密码</label>
            <div class="auth__input">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="4.5" y="10.5" width="15" height="9.5" rx="2" />
                <path d="M8.2 10.5V7.6a3.8 3.8 0 0 1 7.6 0v2.9" />
              </svg>
              <input
                id="password"
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                :autocomplete="isLogin ? 'current-password' : 'new-password'"
                placeholder="至少 8 位"
              />
              <button
                type="button"
                class="auth__eye"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <svg v-if="showPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12Z" />
                  <circle cx="12" cy="12" r="2.8" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9.9 6.2A9.6 9.6 0 0 1 12 5.8c6 0 9.5 6.2 9.5 6.2a17 17 0 0 1-2.7 3.5" />
                  <path d="M6.3 7.9A17 17 0 0 0 2.5 12s3.5 6.2 9.5 6.2a9.3 9.3 0 0 0 4-.9" />
                  <path d="M3 3l18 18" />
                </svg>
              </button>
            </div>
          </div>

          <button type="submit" class="btn--block" :disabled="busy">
            {{ busy ? '处理中…' : isLogin ? '登录' : '注册并开始记账' }}
          </button>
        </form>

        <div class="auth__switch">
          <a @click="toggleMode">
            {{ isLogin ? '还没有账号？去注册' : '已有账号？去登录' }}
          </a>
        </div>
      </div>
    </section>
  </div>
</template>
