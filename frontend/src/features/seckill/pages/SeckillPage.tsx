import { useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  InputNumber,
  Row,
  Space,
  Statistic,
  Steps,
  Typography,
} from 'antd'
import { FireOutlined } from '@ant-design/icons'
import { seckillApi } from '../api'
import { useOrderStatusPolling, useRemain } from '../hooks'
import type { SeckillStatus } from '../types'

interface CurrentOrder {
  orderNo: string
  userId: number
  stockId: number
  acceptedStatus: SeckillStatus
  message: string
}

const STEP_INDEX: Record<string, number> = { QUEUED: 1, SUCCESS: 2, FAILED: 2 }

/** 秒杀演示页：参数 → 余量 → 下单 → 状态轮询（QUEUED → 终态） */
export default function SeckillPage() {
  const { message } = App.useApp()

  const [userId, setUserId] = useState<number>(1001)
  const [stockId, setStockId] = useState<number>(1)
  const [submitting, setSubmitting] = useState(false)
  const [order, setOrder] = useState<CurrentOrder | null>(null)

  const remainQuery = useRemain(stockId)
  const polling = useOrderStatusPolling(
    order && order.acceptedStatus === 'QUEUED'
      ? { orderNo: order.orderNo, userId: order.userId, stockId: order.stockId }
      : null,
  )
  const finalStatus = polling.data?.status ?? order?.acceptedStatus
  const finalMessage = polling.data?.message ?? order?.message

  const doSeckill = async () => {
    setSubmitting(true)
    try {
      const resp = await seckillApi.seckill(userId, stockId)
      setOrder({
        orderNo: resp.orderNo,
        userId,
        stockId,
        acceptedStatus: resp.status,
        message: resp.message,
      })
      if (resp.status === 'QUEUED') {
        message.info(`已受理，单号 ${resp.orderNo}，正在轮询落库结果`)
      }
      remainQuery.refetch()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '下单失败')
    } finally {
      setSubmitting(false)
    }
  }

  const doPreheat = async () => {
    try {
      const preheated = await seckillApi.preheat(stockId)
      message.success(`预热完成，前置库存 ${preheated}`)
      remainQuery.refetch()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预热失败')
    }
  }

  return (
    <Row gutter={[16, 16]}>
      <Col span={8}>
        <Card title="活动参数">
          <Descriptions column={1} size="small">
            <Descriptions.Item label="用户 ID">
              <InputNumber min={1} value={userId} onChange={(v) => v && setUserId(v)} />
            </Descriptions.Item>
            <Descriptions.Item label="商品 ID">
              <InputNumber min={1} value={stockId} onChange={(v) => v && setStockId(v)} />
            </Descriptions.Item>
          </Descriptions>
          <Button
            type="primary"
            danger
            block
            size="large"
            icon={<FireOutlined />}
            loading={submitting}
            onClick={doSeckill}
            style={{ marginTop: 24 }}
          >
            立即抢购
          </Button>
        </Card>
      </Col>

      <Col span={8}>
        <Card title="实时库存（Redis 可售余量）" loading={remainQuery.isLoading}>
          <Statistic
            value={remainQuery.data ?? '—'}
            suffix="件"
            valueStyle={{
              fontSize: 40,
              color: typeof remainQuery.data === 'number' ? '#cf1322' : undefined,
            }}
          />
          {remainQuery.isError && (
            <Typography.Text type="warning">
              {remainQuery.data === undefined ? '库存未预热或缓存不可用（1003）' : ''}
            </Typography.Text>
          )}
          <Space style={{ marginTop: 16 }}>
            <Button onClick={doPreheat}>预热库存</Button>
            <Button
              danger
              onClick={async () => {
                try {
                  await seckillApi.clearCache(stockId)
                  message.success('缓存已清理')
                  remainQuery.refetch()
                } catch (e) {
                  message.error(e instanceof Error ? e.message : '清理失败')
                }
              }}
            >
              清理缓存
            </Button>
          </Space>
        </Card>
      </Col>

      <Col span={8}>
        <Card title="订单进度">
          {order === null ? (
            <Typography.Text type="secondary">尚无订单，点击「立即抢购」发起请求</Typography.Text>
          ) : (
            <>
              <Steps
                direction="vertical"
                size="small"
                current={STEP_INDEX[finalStatus ?? 'QUEUED'] ?? 0}
                status={finalStatus === 'FAILED' ? 'error' : 'process'}
                items={[
                  { title: '提交请求', description: `单号 ${order.orderNo}` },
                  {
                    title: '异步落库（预订单 + Outbox + MQ）',
                    description: finalStatus === 'QUEUED' ? '轮询中…（1s/次）' : finalMessage,
                  },
                  {
                    title: finalStatus === 'FAILED' ? '失败（库存已回补）' : '成功',
                  },
                ]}
              />
              <Alert
                style={{ marginTop: 16 }}
                type={
                  finalStatus === 'SUCCESS' ? 'success' : finalStatus === 'FAILED' ? 'error' : 'info'
                }
                showIcon
                message={finalMessage ?? ''}
              />
            </>
          )}
        </Card>
      </Col>
    </Row>
  )
}
