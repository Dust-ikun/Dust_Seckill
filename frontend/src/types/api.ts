/**
 * 后端统一响应包装（com.dustikun.seckill.Common.result.Result）。
 * code 为字符串（"0" = 成功），success=false 时 data 为 null。
 */
export interface Result<T> {
  code?: string
  message?: string
  data: T | null
  success: boolean
  timestamp?: number
}
