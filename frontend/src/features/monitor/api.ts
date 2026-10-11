import { request } from '@/lib/api/client'
import type {
  ActiveIncidents,
  DiagnosisDetail,
  DiagnosisHistoryItem,
  HistoryPage,
  ToolTrace,
} from './types'

export interface ToolDefinitions {
  count: number
  names: string[]
  definitions: unknown[]
}

/** AI 监控域接口（DiagnosisController） */
export const monitorApi = {
  /** 活跃事故（endTime IS NULL） */
  incidents: (limit = 50) =>
    request<ActiveIncidents>({ url: '/api/ai/incidents', params: { limit } }),

  /** 历史诊断 */
  history: (limit = 50, offset = 0) =>
    request<HistoryPage>({ url: '/api/ai/diagnosis/history', params: { limit, offset } }),

  /** 诊断详情 */
  detail: (incidentId: string) =>
    request<DiagnosisDetail>({
      url: `/api/ai/diagnosis/${encodeURIComponent(incidentId)}`,
    }),

  /** Tool Calling 轨迹 */
  toolTrace: (incidentId: string) =>
    request<ToolTrace>({
      url: `/api/ai/diagnosis/${encodeURIComponent(incidentId)}/tools`,
    }),

  /** 工具白名单 */
  tools: () => request<ToolDefinitions>({ url: '/api/ai/tools' }),
}

export type { DiagnosisHistoryItem }
