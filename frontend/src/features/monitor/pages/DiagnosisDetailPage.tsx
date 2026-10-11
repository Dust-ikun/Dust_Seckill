import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Empty,
  List,
  Progress,
  Row,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
} from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { monitorApi } from '../api'
import StatusTag from '@/components/StatusTag'

function JsonBlock({ title, value }: { title: string; value: unknown }) {
  return (
    <Collapse
      size="small"
      items={[
        {
          key: '1',
          label: title,
          children: (
            <pre style={{ margin: 0, maxHeight: 300, overflow: 'auto', fontSize: 12 }}>
              {JSON.stringify(value, null, 2)}
            </pre>
          ),
        },
      ]}
    />
  )
}

/** 诊断详情页：任务状态 + 结构化结论 + ReAct 工具调用轨迹 */
export default function DiagnosisDetailPage() {
  const { incidentId = '' } = useParams()
  const navigate = useNavigate()

  const detailQuery = useQuery({
    queryKey: ['monitor', 'detail', incidentId],
    queryFn: () => monitorApi.detail(incidentId),
    // 任务未到终态时轮询（后端状态机：诊断完成后不再变化）
    refetchInterval: (q) => {
      const status = q.state.data?.task.status
      return status === 'PENDING' || status === 'RUNNING' || q.state.data === undefined
        ? 3000
        : false
    },
    retry: false,
  })

  const traceQuery = useQuery({
    queryKey: ['monitor', 'trace', incidentId],
    queryFn: () => monitorApi.toolTrace(incidentId),
    enabled: detailQuery.isSuccess,
    refetchInterval: (q) => {
      const status = q.state.data?.status
      return status === 'PENDING' || status === 'RUNNING' ? 3000 : false
    },
    retry: false,
  })

  if (detailQuery.isLoading) {
    return (
      <div style={{ textAlign: 'center', paddingTop: 120 }}>
        <Spin size="large" />
      </div>
    )
  }

  if (detailQuery.isError) {
    return (
      <Empty description={detailQuery.error instanceof Error ? detailQuery.error.message : '加载失败'}>
        <Button onClick={() => navigate('/monitor')}>返回列表</Button>
      </Empty>
    )
  }

  const detail = detailQuery.data!
  const { task, diagnosis } = detail
  const trace = traceQuery.data

  return (
    <Row gutter={[16, 16]}>
      <Col span={24}>
        <Card
          title={
            <Space>
              <Button size="small" icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitor')} />
              <span style={{ fontFamily: 'monospace' }}>{task.incidentId}</span>
              <StatusTag status={task.status} />
              {task.active && <Tag color="processing">活跃中</Tag>}
            </Space>
          }
          extra={detail.summary}
        >
          <Descriptions size="small" column={4} bordered>
            <Descriptions.Item label="服务">{task.serviceName}</Descriptions.Item>
            <Descriptions.Item label="异常类型">{task.alertType}</Descriptions.Item>
            <Descriptions.Item label="级别"><StatusTag status={task.severity} /></Descriptions.Item>
            <Descriptions.Item label="聚合告警">{task.alertCount} 条</Descriptions.Item>
            <Descriptions.Item label="开始">{task.startTime ? new Date(task.startTime).toLocaleString('zh-CN', { hour12: false }) : '—'}</Descriptions.Item>
            <Descriptions.Item label="结束">{task.endTime ? new Date(task.endTime).toLocaleString('zh-CN', { hour12: false }) : '进行中'}</Descriptions.Item>
            <Descriptions.Item label="工具调用">{detail.toolCallCount} 次</Descriptions.Item>
            <Descriptions.Item label="结论产出">{detail.resultCount} 轮</Descriptions.Item>
          </Descriptions>
        </Card>
      </Col>

      <Col span={12}>
        <Card title="诊断结论" size="small">
          {diagnosis === null ? (
            <Empty description="尚无诊断结论（任务可能仍在运行，或 AI 能力未启用）" />
          ) : diagnosis.failureReason ? (
            <Alert type="error" showIcon message="未产出结论" description={diagnosis.failureReason} />
          ) : (
            <Space direction="vertical" style={{ width: '100%' }} size={16}>
              <div>
                <Typography.Text type="secondary">根因</Typography.Text>
                <Typography.Paragraph style={{ marginBottom: 0 }}>{diagnosis.rootCause}</Typography.Paragraph>
              </div>
              {diagnosis.confidence != null && (
                <div>
                  <Typography.Text type="secondary">置信度</Typography.Text>
                  <Progress percent={Math.round(diagnosis.confidence * 100)} status="normal" />
                </div>
              )}
              {diagnosis.evidence.length > 0 && (
                <div>
                  <Typography.Text type="secondary">证据（{diagnosis.evidenceCount}）</Typography.Text>
                  <List
                    size="small"
                    bordered
                    dataSource={diagnosis.evidence}
                    renderItem={(item) => (
                      <List.Item style={{ padding: '6px 12px', fontSize: 13 }}>{item}</List.Item>
                    )}
                  />
                </div>
              )}
              {diagnosis.suggestions.length > 0 && (
                <Alert
                  type="info"
                  message="处理建议"
                  description={
                    <ol style={{ margin: 0, paddingLeft: 18 }}>
                      {diagnosis.suggestions.map((s, i) => (
                        <li key={i} style={{ marginBottom: 4 }}>{s}</li>
                      ))}
                    </ol>
                  }
                />
              )}
              <JsonBlock title="原始返回 rawResult" value={diagnosis.rawResult} />
            </Space>
          )}
        </Card>
      </Col>

      <Col span={12}>
        <Card
          title="ReAct 工具调用轨迹"
          size="small"
          extra={trace?.chain ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>{trace.chain.length} 步</Typography.Text> : null}
        >
          {trace === undefined ? (
            <Spin />
          ) : trace.trace.length === 0 ? (
            <Empty description="无工具调用记录" />
          ) : (
            <Timeline
              items={trace.trace.map((exec) => ({
                color:
                  exec.status === 'SUCCESS'
                    ? 'green'
                    : exec.status === 'FAILED'
                      ? 'red'
                      : 'volcano',
                children: (
                  <div>
                    <Space size={8} wrap>
                      <Tag color="blue">#{exec.sequenceNo}</Tag>
                      <Typography.Text code>{exec.toolName}</Typography.Text>
                      <StatusTag status={exec.status} />
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {exec.executionTimeMs}ms
                      </Typography.Text>
                    </Space>
                    {exec.errorMessage && (
                      <div style={{ color: '#cf1322', fontSize: 12 }}>{exec.errorMessage}</div>
                    )}
                    <div style={{ marginTop: 6 }}>
                      <JsonBlock title="入参 / 返回" value={{ arguments: exec.arguments, result: exec.result }} />
                    </div>
                  </div>
                ),
              }))}
            />
          )}
        </Card>
      </Col>
    </Row>
  )
}
