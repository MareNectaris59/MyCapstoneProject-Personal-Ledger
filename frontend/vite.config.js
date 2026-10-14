import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * 两个关键决定：
 *
 * 1. build.outDir 直接指向后端的静态资源目录。
 *    这样 `npm run build` 的产物无需任何手工拷贝，后端一并托管，
 *    避免"构建产物与托管目录不同步"这类反复出现的问题。
 *    代价是 Vite 会清空该目录 —— 旧的手写 app.js / app.css / vendor 因此被移除，
 *    这是预期的：它们已被 src/ 下的单文件组件取代。
 *
 * 2. 开发期用 proxy 而不是改写 base。
 *    前端在 5173，后端在 8080，接口统一走 /api，由 Vite 转发。
 *    这样前端代码里的请求路径在生产与开发两种模式下完全一致，无需任何分支判断。
 */
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },

  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
    // 记账应用不需要上线 sourcemap
    sourcemap: false,
    chunkSizeWarningLimit: 900
  }
})
