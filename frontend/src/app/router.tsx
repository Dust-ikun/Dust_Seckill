import { lazy } from 'react'
import { Navigate, createBrowserRouter } from 'react-router-dom'
import AppLayout from './layout/AppLayout'
import RequireAuth from '@/features/auth/RequireAuth'

// 路由级代码分割：每个页面一个 chunk
const LoginPage = lazy(() => import('@/features/auth/pages/LoginPage'))
const SeckillPage = lazy(() => import('@/features/seckill/pages/SeckillPage'))
const OpsPage = lazy(() => import('@/features/seckill/pages/OpsPage'))
const IncidentListPage = lazy(() => import('@/features/monitor/pages/IncidentListPage'))
const DiagnosisDetailPage = lazy(() => import('@/features/monitor/pages/DiagnosisDetailPage'))

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/seckill" replace /> },
      { path: 'seckill', element: <SeckillPage /> },
      { path: 'ops', element: <OpsPage /> },
      { path: 'monitor', element: <IncidentListPage /> },
      { path: 'monitor/:incidentId', element: <DiagnosisDetailPage /> },
    ],
  },
])
