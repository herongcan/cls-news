import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * dev 时把 /api 反代到本地 Spring Boot，避免为了联调去后端加 CORS；
 * 生产走「构建产物放进 jar 的 static/」，本来就同源，proxy 不参与。
 */
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/healthz': { target: 'http://127.0.0.1:8080', changeOrigin: true }
    }
  }
})
