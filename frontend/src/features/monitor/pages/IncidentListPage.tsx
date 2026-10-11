import { useNavigate } from 'react-router-dom'
import { Badge, Card, Space, Table, Tabs, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useQuery } from '@tanstack/react-query'
import { monitorApi } from '../api'
import type { DiagnosisHistoryItem } from '../types'
import StatusTag from '@/components/StatusTag'

function renderConfidence(v: number | null) {
  return v == null ? '—' : `${Math.round(v * 100)}%`
}

const columns: ColumnsType<DiagnosisHistoryItem> = [
  {
    title: '事故编号',
    dataIndex: ['task', 'incidentId'],
    key: 'incidentId',
    render: (v: string) => (
      <Typography.Link copyable={{ text: v }} style={{ fontFamily: 'monospace' }}>
        {v}
      </Typography.Link>
    ),
  },
  { title: '服务', dataIndex: ['task', 'serviceName'], key: 'serviceName' },
  { title: '异常类型', dataIndex: ['task', 'alertType'], key: 'alertType' },
  {
    title: '级别',
    dataIndex: ['task', 'severity'],
    key: 'severity',
    render: (v: string) => <StatusTag status={v ?? 'UNKNOWN'} />,
  },
  {
    title: '状态',
    dataIndex: ['task', 'status'],
    key: 'status',
    render: (v: string, row) => (
      <Space>
        <StatusTag status={v ?? '?'} />
        {row.task.active && <Badge status="processing" text="活跃" />}
      </Space>
    ),
  },
  {
    title: '告警数',
    dataIndex: ['task', 'alertCount'],
    key: 'alertCount',
    width: 80,
    align: 'right',
  },
  {
    title: '根因摘要',
    dataIndex: 'rootCause',
    key: 'rootCause',
    ellipsis: { showTitle: true },
    render: (v: string | null, row) =>
      v ? (
        <Tooltip title={row.summary}>{v}</Tooltip>
      ) : (
        <Typography.Text type="secondary">
          {row.failureReason ? `未产出（${row.failureReason}）` : '尚无结论'}
        </Typography.Text>
      ),
  },
  { title: '置信度', dataIndex: 'confidence', key: 'confidence', width: 90, align: 'right', render: renderConfidence },
  { title: '开始时间', dataIndex: ['task', 'startTime'], key: 'startTime', width: 170, render: (v: string | null) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—') },
]

function HistoryTable({ active }: { active: boolean }) {
  const navigate = useNavigate()
  const query = useQuery({
    queryKey: active ? ['monitor', 'incidents'] : ['monitor', 'history'],
    queryFn: (): Promise<{ items: DiagnosisHistoryItem[] }> =>
      active ? monitorApi.incidents(100) : monitorApi.history(100),
    refetchInterval: active ? 5000 : false,
  })

  const items = query.data?.items ?? []

  return (
    <Table<DiagnosisHistoryItem>
      rowKey={(r) => r.task.incidentId}
      columns={columns}
      dataSource={items}
      loading={query.isLoading}
      pagination={{ pageSize: 15, showSizeChanger: false }}
      size="middle"
      onRow={(row) => ({
        onClick: () => navigate(`/monitor/${encodeURIComponent(row.task.incidentId)}`),
        style: { cursor: 'pointer' },
      })}
    />
  )
}

/** AI 监控首页：活跃事故（5s 轮询）+ 历史诊断 */
export default function IncidentListPage() {
  return (
    <Card title="AI 智能诊断 · 事故中心">
      <Tabs
        defaultActiveKey="active"
        items={[
          { key: 'active', label: '活跃事故', children: <HistoryTable active /> },
          { key: 'history', label: '历史诊断', children: <HistoryTable active={false} /> },
        ]}
      />
    </Card>
  )
}
