import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버: /api 요청을 Spring 백엔드(8080)로 프록시 → 브라우저 CORS 불필요.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
