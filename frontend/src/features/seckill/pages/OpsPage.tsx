import { useEffect, useRef, useState } from 'react'
import ReactECharts from 'echarts-for-react'
import {
  Alert,
  App,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  InputNumber,
  Row,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { seckillApi } from '../api'
import { useSeckillMetrics } from '../hooks'
import type { ReconcileReport } from '../types'

interface Snapshot {
  time: string
  queued: number
  consumed: number
  outboxPending: number
}

/** 运行指标页：链路分段的累计读数 + 客户端采样趋势 + 库存对账 */
export default function OpsPage() {
  const { message } = App.useApp()
  const metricsQuery = useSeckillMetrics()
  const m = metricsQuery.data

  // ---- 趋势采样：每次指标到达推一个点，仅保留最近 60 个 ----
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const lastSnapshotRef = useRef<string>('')
  useEffect(() => {
    if (!m) return
    const key = `${m.queued}-${m.consumed}-${m.outboxPending}-${new Date().getMinutes()}`
    if (key === lastSnapshotRef.current) return
    lastSnapshotRef.current = key
    setSnapshots((prev) =>
      [
        ...prev,
        {
          time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
          queued: m.queued,
          consumed: m.consumed,
          outboxPending: m.outboxPending,
        },
      ].slice(-60),
    )
  }, [m])

  // ---- 对账 ----
  const [stockId, setStockId] = useState<number>(1)
  const [repair, setRepair] = useState(false)
  const [reconciling, setReconciling] = useState(false)
  const [report, setReport] = useState<ReconcileReport | null>(null)

  const doReconcile = async () => {
    if (repair) {
      message.warning('repair=true 会改写 Redis 库存，请确认链路已停止写入')
    }
    setReconciling(true)
    try {
      setReport(await seckillApi.reconcile(stockId, repair))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '对账失败')
    } finally {
      setReconciling(false)
    }
  }

  const trendOption = {
    animation: false,
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['queued(受理)', 'consumed(落库)', 'outboxPending(待投递)'] },
    grid: { left: 48, right: 16, top: 40, bottom: 24 },
    xAxis: { type: 'category' as const, data: snapshots.map((s) => s.time) },
    yAxis: { type: 'value' as const, minInterval: 1 },
    series: [
      {
        name: 'queued(受理)',
        type: 'line' as const,
        showSymbol: false,
        data: snapshots.map((s) => s.queued),
        itemStyle: { color: '#faad14' },
      },
      {
        name: 'consumed(落库)',
        type: 'line' as const,
        showSymbol: false,
        data: snapshots.map((s) => s.consumed),
        itemStyle: { color: '#52c41a' },
      },
      {
        name: 'outboxPending(待投递)',
        type: 'line' as const,
        showSymbol: false,
        data: snapshots.map((s) => s.outboxPending),
        itemStyle: { color: '#1677ff' },
      },
    ],
  }

  const actionColumns: ColumnsType<{ action: string }> = [
    { title: '动作', dataIndex: 'action', key: 'action' },
  ]

  return (
    <Row gutter={[16, 16]}>
      <Col span={24}>
        <Card
          title={
            <Space>
              运行态总账
              {m && (
                <Badge
                  status={m.mqEnabled ? 'success' : 'error'}
                  text={m.mqEnabled ? 'MQ 已启用' : 'MQ 未启用（降级同步落库）'}
                />
              )}
            </Space>
          }
          loading={metricsQuery.isLoading}
          extra={<Typography.Text type="secondary">5s 自动刷新 · 进程内累计值，重启归零</Typography.Text>}
        >
          {m && (
            <>
              <Row gutter={[16, 16]}>
                <Col span={6}>
                  <Card type="inner" title="请求侧">
                    <Row gutter={[8, 8]}>
                      <Col span={12}><Statistic title="已受理" value={m.queued} /></Col>
                      <Col span={12}><Statistic title="降级写库" value={m.degraded} /></Col>
                      <Col span={12}><Statistic title="同步成功" value={m.syncSuccess} /></Col>
                      <Col span={12}><Statistic title="预扣回滚" value={m.rollback} /></Col>
                    </Row>
                  </Card>
                </Col>
                <Col span={6}>
                  <Card type="inner" title="待投递侧（Outbox）">
                    <Row gutter={[8, 8]}>
                      <Col span={12}><Statistic title="待投递" value={m.outboxPending} valueStyle={m.outboxPending > 0 ? { color: '#faad14' } : undefined} /></Col>
                      <Col span={12}><Statistic title="已投出" value={m.outboxSent} /></Col>
                      <Col span={12}><Statistic title="重试中" value={m.outboxFailed} valueStyle={m.outboxFailed > 0 ? { color: '#cf1322' } : undefined} /></Col>
                      <Col span={12}><Statistic title="已放弃" value={m.outboxAbandoned} /></Col>
                    </Row>
                  </Card>
                </Col>
                <Col span={6}>
                  <Card type="inner" title="消费侧">
                    <Row gutter={[8, 8]}>
                      <Col span={12}><Statistic title="已确认" value={m.consumed} /></Col>
                      <Col span={12}><Statistic title="幂等重复" value={m.consumedDuplicate} /></Col>
                      <Col span={12}><Statistic title="订单缺失" value={m.consumedOrderMissing} valueStyle={m.consumedOrderMissing > 0 ? { color: '#cf1322' } : undefined} /></Col>
                      <Col span={12}><Statistic title="在途估算" value={m.inFlightEstimate} /></Col>
                    </Row>
                  </Card>
                </Col>
                <Col span={6}>
                  <Card type="inner" title="补偿侧">
                    <Row gutter={[8, 8]}>
                      <Col span={12}><Statistic title="已补偿" value={m.compensated} /></Col>
                      <Col span={12}><Statistic title="待补偿" value={m.compensatePending} valueStyle={m.compensatePending > 0 ? { color: '#faad14' } : undefined} /></Col>
                      <Col span={12}><Statistic title="登记数" value={m.compensateEnqueued} /></Col>
                      <Col span={12}><Statistic title="已恢复" value={m.compensateRecovered} /></Col>
                    </Row>
                  </Card>
                </Col>
              </Row>

              {Object.keys(m.reconcileFindings ?? {}).length > 0 && (
                <Alert
                  style={{ marginTop: 16 }}
                  type="warning"
                  showIcon
                  message="对账发现不一致"
                  description={
                    <Space size={[8, 4]} wrap>
                      {Object.entries(m.reconcileFindings).map(([k, v]) => (
                        <Tag key={k} color="orange">
                          {k}: {v}
                        </Tag>
                      ))}
                    </Space>
                  }
                />
              )}
            </>
          )}
        </Card>
      </Col>

      <Col span={12}>
        <Card title="链路趋势（本页打开期间采样）">
          <ReactECharts option={trendOption} style={{ height: 300 }} notMerge />
        </Card>
      </Col>

      <Col span={12}>
        <Card title="库存对账">
          <Space style={{ marginBottom: 16 }}>
            <InputNumber min={1} value={stockId} onChange={(v) => v && setStockId(v)} addonBefore="商品 ID" />
            <span>
              修复模式 <Switch checked={repair} onChange={setRepair} />
            </span>
            <Button type="primary" loading={reconciling} onClick={doReconcile}>
              执行对账
            </Button>
          </Space>

          {report && (
            <>
              <Alert
                type={report.status === 'CONSISTENT' ? 'success' : 'warning'}
                showIcon
                message={<Space>{report.status === 'CONSISTENT' ? '一致' : report.status}{report.repaired && <Tag color="purple">已执行修复</Tag>}</Space>}
                description={report.conclusion}
                style={{ marginBottom: 16 }}
              />
              <Descriptions size="small" column={2} bordered>
                <Descriptions.Item label="Redis 库存">{report.redisStock ?? '—'}</Descriptions.Item>
                <Descriptions.Item label="数据库库存">{report.dbStock}</Descriptions.Item>
                <Descriptions.Item label="可信在途">{report.expectedInFlight}（{report.inFlightSource}）</Descriptions.Item>
                <Descriptions.Item label="PENDING 预订单">{report.pendingOrders}</Descriptions.Item>
                <Descriptions.Item label="已放弃未了结">{report.abandonedPending}</Descriptions.Item>
                <Descriptions.Item label="未了结补偿">{report.unresolvedCompensations}</Descriptions.Item>
                <Descriptions.Item label="Redis 已购标记">{report.redisBoughtCount}</Descriptions.Item>
                <Descriptions.Item label="数据库订单数">{report.dbOrderCount}</Descriptions.Item>
              </Descriptions>
              {report.actions.length > 0 && (
                <Table
                  style={{ marginTop: 12 }}
                  size="small"
                  rowKey={(r) => r.action}
                  columns={actionColumns}
                  dataSource={report.actions.map((action) => ({ action }))}
                  pagination={false}
                  title={() => '修复动作'}
                />
              )}
            </>
          )}
        </Card>
      </Col>
    </Row>
  )
}
