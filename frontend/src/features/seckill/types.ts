/**
 * 与后端 DTO 对齐的类型（SeckillOrderResponse / ReconcileReport / /seckill/metrics）。
 * 后端加状态/字段时只改这里。
 */

/** 下单受理状态机：QUEUED（已受理待落库）→ SUCCESS / FAILED */
export type SeckillStatus = 'QUEUED' | 'SUCCESS' | 'FAILED'

export interface SeckillOrderResponse {
  orderNo: string
  status: SeckillStatus
  message: string
}

export interface ReconcileReport {
  stockId: number
  status: string
  redisStock: number | null
  dbStock: number
  expectedInFlight: number
  inFlightSource: string
  pendingOrders: number
  abandonedPending: number
  stalePending: number
  unresolvedCompensations: number
  redisBoughtCount: number
  dbOrderCount: number
  repaired: boolean
  actions: string[]
  conclusion: string
}

/** GET /seckill/metrics 的人读视图（字段与 SeckillController#metrics 一致） */
export interface SeckillMetrics {
  // 请求侧
  queued: number
  degraded: number
  rollback: number
  restoreStockOnly: number
  syncSuccess: number
  // 待投递侧
  outboxEnqueued: number
  outboxSent: number
  outboxRetried: number
  outboxAbandoned: number
  outboxPurged: number
  outboxPending: number
  outboxFailed: number
  // MQ 投递侧
  mqEnabled: boolean
  mqSent: number
  mqSendFailed: number
  // 消费侧
  consumed: number
  consumedDuplicate: number
  consumedOrderMissing: number
  consumeCancelled: number
  consumeFailed: number
  compensated: number
  inFlightEstimate: number
  // 补偿侧
  compensateEnqueued: number
  compensateRecovered: number
  compensateAbandoned: number
  compensatePending: number
  // 对账侧（按结论分组的计数）
  reconcileFindings: Record<string, number>
}
