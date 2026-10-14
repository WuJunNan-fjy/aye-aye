import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// Aye 前端 v2：标准 Vite standalone，不依赖 workspace / __DSH_BOOT__
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  css: {
    modules: {
      // 复刻 dsh 的 CSS Module hash 策略：_名称_短hash
      generateScopedName: function (name: string, filename: string): string {
        const safe = name.replace(/[^A-Za-z0-9_-]/g, '')
        const filePart = path.basename(filename).replace(/\.(module|client)\.(css|scss)$/i, '')
        const short = filePart.substring(0, 4).replace(/[^A-Za-z0-9]/g, '')
        return `_${safe}_${short}`
      },
    },
  },
  server: {
    port: 5174,
    host: '127.0.0.1',
    strictPort: false,
  },
})
