import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// В разработке /api проксируется на бэкенд, чтобы браузер считал API тем же origin
// и refresh-cookie работал без CORS.
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