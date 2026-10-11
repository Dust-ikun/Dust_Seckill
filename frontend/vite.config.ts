import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 后端业务端口（application.yaml: server.port=8081）
  const backendOrigin = env.VITE_BACKEND_ORIGIN ?? 'http://localhost:8081'

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/seckill': { target: backendOrigin, changeOrigin: true },
        '/api/ai': { target: backendOrigin, changeOrigin: true },
      },
    },
  }
})
