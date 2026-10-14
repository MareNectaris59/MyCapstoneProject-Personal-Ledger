import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/brutalism.css'

/**
 * 应用入口。
 *
 * 样式只有一份 brutalism.css —— 设计令牌与组件样式集中在那处，
 * 与技能要求的「语义化令牌、组件里不写裸 hex」一致。
 *
 * Element Plus 暂未接入：当前组件全部手写，因为 Brutalism 需要完全控制
 * 边框、阴影、圆角（必须为 0），而这些恰好是组件库最难覆盖的部分。
 * 后续若引入，应在 brutalism.css 里覆盖其 CSS 变量，而不是逐个改组件。
 */
createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
