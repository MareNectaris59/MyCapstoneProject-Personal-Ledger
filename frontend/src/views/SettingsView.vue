<script setup>
/**
 * 账号设置。
 *
 * 目前只有注销账号一项 —— 系统中唯一不可逆的操作，所以单独成页，
 * 不和日常记账功能混在一起，避免误触。
 *
 * 交互上做了三重防误操作：
 *   1. 危险区先折叠，需要主动点开
 *   2. 必须手动输入「注销账号」四个字，不能只点按钮
 *   3. 必须输入登录密码（后端也会二次校验）
 */
import { ref, computed } from 'vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const expanded = ref(false)
const confirmText = ref('')
const password = ref('')
const reason = ref('')
const busy = ref(false)
const errorMsg = ref('')

/** 需要用户逐字输入的确认短语 */
const CONFIRM_PHRASE = '注销账号'

const canSubmit = computed(
  () => confirmText.value.trim() === CONFIRM_PHRASE && password.value.length > 0 && !busy.value
)

function reset() {
  confirmText.value = ''
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
    // 不复用 auth.logout()，因为它会再去调 /auth/logout —— 此时令牌已被吊销，
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
    <div class="panel__body">
      <table>
        <tbody>
          <tr>
            <td class="muted" style="width: 120px">昵称</td>
            <td>{{ auth.user?.nickname || '—' }}</td>
          </tr>
          <tr>
            <td class="muted">邮箱</td>
            <td class="mono">{{ auth.user?.email || '—' }}</td>
          </tr>
          <tr>
            <td class="muted">本周起始日</td>
            <td>{{ auth.user?.weekStart === 7 ? '周日' : '周一' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head" style="background: var(--c-red); color: #fff; border-color: var(--color-border)">
      危险操作
    </div>
    <div class="panel__body">
      <p style="margin-top: 0">
        <b>注销账号会删除你的账号，这不是退出登录。</b>
      </p>
      <ul class="muted" style="font-size: 13px; line-height: 1.9; margin: 0 0 12px; padding-left: 20px">
        <li>账号立即失效，<b>无法再登录</b>，也无法找回</li>
        <li>昵称、头像、邮箱等个人信息会被清除</li>
        <li>你的账务记录（收支流水）会被保留但无法再访问</li>
        <li>该邮箱之后可以重新注册一个全新账号，但旧数据不会恢复</li>
      </ul>

      <button v-if="!expanded" class="btn--danger" @click="expanded = true">
        我要注销账号
      </button>

      <template v-else>
        <div class="alert alert--error">
          请确认这是你本人的操作，并且已经不再需要这个账号里的数据。
        </div>

        <div v-if="errorMsg" class="alert alert--error">{{ errorMsg }}</div>

        <div class="field">
          <label class="field__label" for="confirm-text">
            请输入「{{ CONFIRM_PHRASE }}」以确认
          </label>
          <input
            id="confirm-text"
            v-model="confirmText"
            autocomplete="off"
            :placeholder="CONFIRM_PHRASE"
          />
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
          class="btn btn--ghost"
          style="margin-left: 8px"
          :disabled="busy"
          @click="expanded = false; reset()"
        >
          取消
        </button>
      </template>
    </div>
  </div>
</template>
