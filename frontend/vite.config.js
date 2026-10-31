import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端独立开发：/api 代理到本地 Spring Boot（9900）
// 生产构建产物输出到后端静态资源目录，由 Spring Boot 直接托管
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: false
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9900', changeOrigin: true }
    }
  }
})
