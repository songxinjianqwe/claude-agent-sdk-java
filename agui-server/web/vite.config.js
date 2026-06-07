import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// AG-UI 适配前端：dev 端口 5180，/agui 走 proxy 到 agui-server(8095)
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180,
    proxy: {
      '/agui': {
        target: 'http://localhost:8095',
        changeOrigin: true,
      },
    },
  },
})
