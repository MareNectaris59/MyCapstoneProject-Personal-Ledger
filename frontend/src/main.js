import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/theme.css'
import './assets/aero.css'   // Frutiger Aero material layer (experiment branch)

/**
 * 应用入口。
 *
 * 样式只有一份 theme.css —— 设计令牌与组件样式集中在那处，
 * 与技能要求的「语义化令牌、组件里不写裸 hex」一致。
 * 风格为轻量 Claymorphism，令牌来源与两处刻意偏离见 theme.css 顶部注释。
 *
 * Element Plus 暂未接入：当前组件全部手写，因为该风格的圆角与双层阴影
 * 需要完全控制。后续若引入，应在 theme.css 里覆盖其 CSS 变量，而不是逐个改组件。
 */
createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
