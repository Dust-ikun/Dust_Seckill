/**
 * AI 监控域类型（对齐 DiagnosisQueryService 返回的 Map 结构）。
 * task/diagnosis/trace 的字段与 ai_diagnosis_task / ai_diagnosis_result / ai_tool_execution 逐列对应。
 */

export interface DiagnosisTask {
  id: number
  incidentId: string
  serviceName: string
  alertType: string
  severity: string
  status: string
  startTime: string | null
  endTime: string | null
  alertId: string | null
  alertCount: number
  active: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface DiagnosisResult {
  severity: string | null
  rootCause: string | null
  confidence: number | null
  impact: Record<string, unknown>
  evidence: string[]
  evidenceCount: number
  suggestions: string[]
  failureReason?: string
  rawResult: Record<string, unknown>
  createdAt: string | null
}

/** GET /api/ai/diagnosis/{incidentId} */
export interface DiagnosisDetail {
  task: DiagnosisTask
  diagnosis: DiagnosisResult | null
  resultCount: number
  toolCallCount: number
  summary: string
}

/** GET /api/ai/diagnosis/{incidentId}/tools 的单条轨迹 */
export interface ToolExecution {
  sequenceNo: number
  toolName: string
  status: string
  arguments: Record<string, unknown>
  result: Record<string, unknown>
  executionTimeMs: number
  errorMessage: string | null
  createdAt: string | null
}

export interface ToolTrace {
  incidentId: string
  taskId: number
  status: string | null
  toolCallCount: number
  chain: string[]
  trace: ToolExecution[]
}

/** 历史诊断 / 活跃事故列表的条目（task + 最新结论摘要） */
export interface DiagnosisHistoryItem {
  task: DiagnosisTask
  rootCause: string | null
  confidence: number | null
  evidenceCount: number
  failureReason: string | null
  summary: string
}

export interface HistoryPage {
  count: number
  limit: number
  offset: number
  items: DiagnosisHistoryItem[]
}

export interface ActiveIncidents {
  activeCount: number
  items: DiagnosisHistoryItem[]
}
