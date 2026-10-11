import { Tag } from 'antd'

/** 状态 → 颜色映射（订单 / 诊断任务 / 工具执行 / 对账结论共用） */
const STATUS_COLOR: Record<string, string> = {
  // 订单状态机
  QUEUED: 'gold',
  SUCCESS: 'green',
  FAILED: 'red',
  // 诊断任务状态机
  PENDING: 'gold',
  RUNNING: 'processing',
  COMPLETED: 'green',
  SKIPPED: 'default',
  // 工具执行
  REJECTED: 'volcano',
  // 对账结论
  CONSISTENT: 'green',
  INCONSISTENT: 'red',
  MANUAL_CHECK: 'orange',
  DEGRADED: 'purple',
}

/** 未知状态走灰色兜底，新增后端状态不需要改组件 */
export default function StatusTag({ status }: { status: string }) {
  return <Tag color={STATUS_COLOR[status] ?? 'default'}>{status}</Tag>
}
