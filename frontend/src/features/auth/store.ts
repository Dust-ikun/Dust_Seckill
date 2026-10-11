import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * 鉴权壳子（预留）。
 * 当前阶段 `enabled=false` 即游客直通，路由守卫放行；
 * 后端接入登录后把 enabled 置 true 并由 LoginPage 换取真实 token，前端结构无需变动。
 */
export interface AuthState {
  enabled: boolean
  token: string | null
  username: string | null
  setToken: (token: string, username: string) => void
  setEnabled: (enabled: boolean) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      enabled: false,
      token: null,
      username: null,
      setToken: (token, username) => set({ token, username }),
      setEnabled: (enabled) => set({ enabled }),
      clear: () => set({ token: null, username: null }),
    }),
    { name: 'ikun-seckill-auth' },
  ),
)
