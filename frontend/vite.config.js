import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// В разработке запросы к /api проксируются на бэкенд.
// Благодаря этому браузер считает API тем же origin,
// и refresh-cookie работает без CORS-проблем.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false
      }
    }
  }
})