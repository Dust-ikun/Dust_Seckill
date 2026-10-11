import { useQuery } from '@tanstack/react-query'
import { seckillApi } from './api'
import type { SeckillStatus } from './types'

/** Redis 剩余库存轮询（未预热时后端返回业务失败，静默显示「未就绪」） */
export function useRemain(stockId: number, intervalMs = 3000) {
  return useQuery({
    queryKey: ['seckill', 'remain', stockId],
    queryFn: () => seckillApi.remain(stockId),
    refetchInterval: intervalMs,
    retry: false,
  })
}

/** 运行指标轮询（OpsPage 趋势采样也复用它） */
export function useSeckillMetrics(intervalMs = 5000, enabled = true) {
  return useQuery({
    queryKey: ['seckill', 'metrics'],
    queryFn: () => seckillApi.metrics(),
    refetchInterval: intervalMs,
    enabled,
  })
}

/**
 * 订单状态轮询：仅 QUEUED 时每秒拉一次，终态自动停。
 * 后端目前没有推送通道，轮询是约定俗成的客户端义务（见 SeckillController 注释）。
 */
export function useOrderStatusPolling(
  order: { orderNo: string; userId: number; stockId: number } | null,
) {
  return useQuery({
    queryKey: ['seckill', 'order', order?.orderNo, order?.userId, order?.stockId],
    queryFn: () => seckillApi.orderStatus(order!.orderNo, order!.userId, order!.stockId),
    enabled: order !== null,
    refetchInterval: (query) => {
      const status: SeckillStatus | undefined = query.state.data?.status
      return status === 'QUEUED' || status === undefined ? 1000 : false
    },
  })
}
