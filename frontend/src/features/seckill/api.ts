import { request } from '@/lib/api/client'
import type { ReconcileReport, SeckillMetrics, SeckillOrderResponse } from './types'

/** seckill 域接口定义：路径/参数集中在这里，页面只调用方法 */
export const seckillApi = {
  /** 秒杀下单（返回「已受理」，QUEUED 需轮询 orderStatus） */
  seckill: (userId: number, stockId: number) =>
    request<SeckillOrderResponse>({ method: 'POST', url: '/seckill', params: { userId, stockId } }),

  /** 查单（判据以订单状态优先） */
  orderStatus: (orderNo: string, userId: number, stockId: number) =>
    request<SeckillOrderResponse>({
      url: '/seckill/order/status',
      params: { orderNo, userId, stockId },
    }),

  /** Redis 剩余库存（真实可售余量） */
  remain: (stockId: number) => request<number>({ url: '/seckill/remain', params: { stockId } }),

  /** 库存预热（活动前必须执行，否则下单返回 1003） */
  preheat: (stockId: number) =>
    request<number>({ method: 'POST', url: '/seckill/preheat', params: { stockId } }),

  /** 清理活动缓存 */
  clearCache: (stockId: number) =>
    request<null>({ method: 'DELETE', url: '/seckill/cache', params: { stockId } }),

  /** 人读运行指标 */
  metrics: () => request<SeckillMetrics>({ url: '/seckill/metrics' }),

  /** 库存对账（repair=true 会改写 Redis，谨慎） */
  reconcile: (stockId: number, repair = false) =>
    request<ReconcileReport>({ url: '/seckill/reconcile', params: { stockId, repair } }),
}
