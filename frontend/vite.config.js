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
    chunkSizeWarningLimit: 2000,

    // ================================================================
    // 为什么用 terser 而不是默认的 esbuild 压缩
    // ================================================================
    //
    // 默认（minify: 'esbuild'）在本机会构建失败：
    //
    //   [vite:esbuild-transpile] remove C:\...\Temp\esbuild-<hash>: Access is denied.
    //
    // 原因链路：
    //   1. `build.minify === 'esbuild'`（默认）会让 Vite 注册
    //      `vite:esbuild-transpile` 插件 —— 触发条件是这个选项，
    //      不是 `build.target`。
    //   2. 该插件把降级任务交给 esbuild 的 Go 子进程。
    //   3. 产物较大时（引入 ECharts 后 AnalysisView 分包到 MB 级），
    //      esbuild 通过"临时文件"而不是 stdio 传递数据；
    //      它写完后再删除该临时文件，在这台机器上被拒绝（安全软件锁文件），
    //      于是整个构建失败。
    //
    // 换成 terser 后完全不经过 esbuild 的临时文件路径，构建稳定通过。
    // 代价是多一个 devDependency（terser），收益是能正常出包。
    //
    // 注意：`--minify=false` 也能绕过（同样是因为不再走 esbuild），
    // 但产物大 3 倍（AnalysisView 1.5MB vs 压缩后约 500KB），不建议作为默认值。
    minify: 'terser',

    // 与上面的 minify 配合，两者缺一不可：
    //   - target 非 'esnext' 时 Vite 会注册 vite:esbuild-transpile（做语法降级）
    //   - minify 决定压缩器
    // 只设其中一个，esbuild 仍会被调用并触发那个临时文件删除失败。
    // 交付形态是本地/毕设演示，浏览器都是现代版本，不需要降级。
    target: 'esnext',

    terserOptions: {
      compress: { drop_console: true, drop_debugger: true },
      format: { comments: false }
    },

    // ECharts 分包较大，但它是懒加载的（只进 /analysis 页），
    // 不影响其它页面的首屏体积。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) {
            return 'echarts'
          }
        }
      }
    }
  }
})
