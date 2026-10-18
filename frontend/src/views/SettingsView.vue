<script setup>
/**
 * 账号设置。
 *
 * 三件事：改昵称、改密码、注销账号。
 *
 * 改密码和注销都要求校验原密码/邮箱，理由相同：
 *   - 邮箱证明"你是这个账号的主人"（邮箱是账号的唯一标识）
 *   - 密码证明"你是本人"（防止设备被他人短暂接触）
 * 只凭一个已登录的会话就允许改密码或注销，等于把账号交给任何摸到设备的人。
 *
 * 注销的确认方式演化过：上一版要求逐字输入「注销账号」四个字。那个模式
 * （GitHub 用仓库名、Vercel 用项目名）适用于批量或破坏性删除，用在"注销
 * 自己的账号"上很别扭 —— 用户会想"我当然知道我要注销，为什么还要我抄一遍"。
 */
import { ref, computed } from 'vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const expanded = ref(false)
const emailInput = ref('')
const password = ref('')
const reason = ref('')
const busy = ref(false)
const errorMsg = ref('')

/* ---------------- 改昵称 ---------------- */

const nickname = ref(auth.user?.nickname || '')
const savingNickname = ref(false)

const nicknameChanged = computed(
  () => nickname.value.trim().length > 0 && nickname.value.trim() !== (auth.user?.nickname || '')
)

async function saveNickname() {
  if (!nicknameChanged.value) return
  savingNickname.value = true
  try {
    const info = await api.updateProfile({ nickname: nickname.value.trim() })
    // 用后端返回的最新用户信息刷新侧边栏，避免再发一次 /auth/me
    auth.user = { ...auth.user, ...info }
    auth.notify('ok', '昵称已更新')
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    savingNickname.value = false
  }
}

/* ---------------- 改密码 ---------------- */

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const savingPassword = ref(false)
const passwordError = ref('')

/** 与后端 / 注册表单保持一致：8-64 位 */
const newPasswordValid = computed(
  () => newPassword.value.length >= 8 && newPassword.value.length <= 64
)
const confirmMatches = computed(
  () => confirmPassword.value.length > 0 && confirmPassword.value === newPassword.value
)
const canSavePassword = computed(
  () =>
    oldPassword.value.length > 0 &&
    newPasswordValid.value &&
    confirmMatches.value &&
    !savingPassword.value
)

function resetPasswordForm() {
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  passwordError.value = ''
}

async function savePassword() {
  passwordError.value = ''
  if (!canSavePassword.value) return
  savingPassword.value = true
  try {
    await api.changePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value
    })
    resetPasswordForm()
    // 服务端是无状态 JWT，旧令牌在过期前仍有效。主动清掉本地令牌并回登录页，
    // 是本设备立即失效的最直接方式。见后端 UserService.changePassword 的说明。
    auth.notify('ok', '密码已修改，请用新密码重新登录')
    setTimeout(async () => {
      await auth.logout()
      location.hash = '#/login'
    }, 1200)
  } catch (e) {
    passwordError.value = e.message
  } finally {
    savingPassword.value = false
  }
}

/** 邮箱比对不区分大小写 —— 用户不该因为大小写被拦住 */
const emailMatches = computed(() => {
  const registered = (auth.user?.email || '').trim().toLowerCase()
  const typed = emailInput.value.trim().toLowerCase()
  return registered.length > 0 && typed === registered
})

/** 只有当用户输入了东西、但和注册邮箱不一致时才提示，避免一上来就报错 */
const emailWrong = computed(
  () => emailInput.value.trim().length > 0 && !emailMatches.value
)

const canSubmit = computed(
  () => emailMatches.value && password.value.length > 0 && !busy.value
)

function reset() {
  emailInput.value = ''
  password.value = ''
  reason.value = ''
  errorMsg.value = ''
}

async function doDelete() {
  errorMsg.value = ''
  if (!canSubmit.value) return

  busy.value = true
  try {
    await api.deleteAccount({ password: password.value, reason: reason.value })
    // 注销成功后本次会话已失效，直接清空本地状态回登录页。
    // 不复用 auth.logout()，因为它会再调 /auth/logout —— 此时令牌已被吊销，
    // 那个请求必然失败，只会产生一条误导性的错误日志。
    sessionStorage.clear()
    auth.user = null
    auth.notify('ok', '账号已注销，正在返回登录页…')
    setTimeout(() => {
      location.hash = '#/login'
      location.reload()
    }, 1200)
  } catch (e) {
    errorMsg.value = e.message
    busy.value = false
  }
}
</script>

<template>
  <div class="page-head">
    <h2>账号设置</h2>
  </div>

  <div class="panel">
    <div class="panel__head">账号信息</div>
    <div class="panel__body" style="padding-top: 0">
      <table>
        <tbody>
          <tr>
            <td class="muted" style="width: 120px">昵称</td>
            <td>
              <div class="inline-edit">
                <input
                  id="nickname"
                  v-model="nickname"
                  maxlength="50"
                  autocomplete="nickname"
                  @keyup.enter="saveNickname"
                />
                <button
                  class="btn--sm"
                  :disabled="!nicknameChanged || savingNickname"
                  @click="saveNickname"
                >
                  {{ savingNickname ? '保存中…' : '保存' }}
                </button>
              </div>
            </td>
          </tr>
          <tr>
            <td class="muted">邮箱</td>
            <td class="mono">{{ auth.user?.email || '—' }}</td>
          </tr>
          <tr>
            <td class="muted">每周起始日</td>
            <td>{{ auth.user?.weekStart === 7 ? '周日' : '周一' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head">修改密码</div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="passwordError" class="alert alert--error" style="margin-bottom: 12px">
        {{ passwordError }}
      </div>

      <div class="field">
        <label class="field__label" for="old-password">当前密码</label>
        <input
          id="old-password"
          v-model="oldPassword"
          type="password"
          autocomplete="current-password"
        />
      </div>

      <div class="field">
        <label class="field__label" for="new-password">新密码</label>
        <input
          id="new-password"
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
        />
        <div v-if="newPassword.length > 0 && !newPasswordValid" class="field__hint" style="color: var(--danger-text)">
          密码长度需在 8-64 位之间
        </div>
      </div>

      <div class="field">
        <label class="field__label" for="confirm-password">再输一次新密码</label>
        <input
          id="confirm-password"
          v-model="confirmPassword"
          type="password"
          autocomplete="new-password"
          @keyup.enter="savePassword"
        />
        <div v-if="confirmPassword.length > 0 && !confirmMatches" class="field__hint" style="color: var(--danger-text)">
          两次输入不一致
        </div>
      </div>

      <button :disabled="!canSavePassword" @click="savePassword">
        {{ savingPassword ? '修改中…' : '修改密码' }}
      </button>
      <div class="field__hint" style="margin-top: 8px">修改成功后需要用新密码重新登录。</div>
    </div>
  </div>

  <div class="danger-zone">
    <h3>注销账号</h3>
    <p>注销会删除你的账号，注销后原账号将无法再次登录，相关记账数据也将无法找回。</p>

    <button v-if="!expanded" class="btn--danger" style="margin-top: 12px" @click="expanded = true">
      我要注销账号
    </button>

    <template v-else>
      <div v-if="errorMsg" class="alert alert--error" style="margin-top: 12px">{{ errorMsg }}</div>

      <div class="field" style="margin-top: 16px">
        <label class="field__label" for="confirm-email">输入你的注册邮箱以确认</label>
        <input
          id="confirm-email"
          v-model="emailInput"
          type="email"
          autocomplete="off"
          :placeholder="auth.user?.email || 'you@example.com'"
        />
        <div v-if="emailWrong" class="field__hint" style="color: var(--danger-text)">
          与注册邮箱不一致
        </div>
      </div>

      <div class="field">
        <label class="field__label" for="confirm-pwd">登录密码</label>
        <input
          id="confirm-pwd"
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="用于确认是你本人"
        />
      </div>

      <div class="field">
        <label class="field__label" for="confirm-reason">注销原因（选填）</label>
        <input id="confirm-reason" v-model="reason" maxlength="255" placeholder="帮助我们改进" />
      </div>

      <button class="btn--danger" :disabled="!canSubmit" @click="doDelete">
        {{ busy ? '正在注销…' : '确认注销账号' }}
      </button>
      <button
        class="btn--ghost"
        style="margin-left: 8px"
        :disabled="busy"
        @click="expanded = false; reset()"
      >
        取消
      </button>
    </template>
  </div>
</template>
<style scoped>
/* 昵称就地编辑：输入框 + 保存按钮同一行，宽度受控 */
.inline-edit {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.inline-edit input {
  max-width: 260px;
}
</style>