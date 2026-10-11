import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import type { Result } from '@/types/api'
import { useAuthStore } from '@/features/auth/store'

/** 业务错误：后端返回 success=false（code 为业务错误码，如 1003 库存未预热） */
export class ApiError extends Error {
  readonly code: string
  constructor(code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * 全站唯一的 axios 实例。
 * 网关地址、鉴权头、错误码翻译都在这一处收口——
 * 后续接入登录态/灰度/多环境，只改这里，业务代码零感知。
 */
const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10_000,
})

client.interceptors.request.use((config) => {
  const { enabled, token } = useAuthStore.getState()
  if (enabled && token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 解包 Result<T>：success=false 或 HTTP 错误统一抛 ApiError，成功直接返回 data */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const resp = await client.request<Result<T>>(config)
    const body = resp.data
    if (!body?.success) {
      throw new ApiError(body?.code ?? 'UNKNOWN', body?.message ?? '请求失败')
    }
    return body.data as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    if (axios.isAxiosError(e)) {
      const body = e.response?.data as Result<unknown> | undefined
      // 后端 404/500 也会带 Result 结构（如 DiagnosisController），优先读它的业务语义
      if (body && typeof body === 'object' && 'success' in body && !body.success) {
        throw new ApiError(
          body.code ?? String(e.response?.status ?? 'HTTP_ERROR'),
          body.message ?? '请求失败',
        )
      }
      throw new ApiError('NETWORK_ERROR', e.message || '网络错误或服务不可用')
    }
    throw e
  }
}
