import { Navigate, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuthStore } from './store'

/** 路由守卫：auth.enabled=false 时游客直通（当前默认）；开启后未登录跳 /login */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { enabled, token } = useAuthStore()
  const location = useLocation()

  if (enabled && !token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}
