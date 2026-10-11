import { Suspense } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Avatar, Button, Layout, Menu, Space, Spin, Typography } from 'antd'
import {
  AimOutlined,
  DashboardOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/features/auth/store'

const { Sider, Header, Content } = Layout

const MENU_ITEMS = [
  { key: '/seckill', icon: <ThunderboltOutlined />, label: '秒杀演示' },
  { key: '/ops', icon: <DashboardOutlined />, label: '运行指标' },
  { key: '/monitor', icon: <AimOutlined />, label: 'AI 监控' },
]

/** 应用骨架：左侧导航 + 顶栏 + 内容区（路由懒加载挂到这里） */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { enabled, username, clear } = useAuthStore()

  const selectedKey =
    MENU_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? '/seckill'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" collapsible>
        <div
          style={{
            color: '#fff',
            fontWeight: 700,
            textAlign: 'center',
            padding: '18px 0',
            fontSize: 16,
          }}
        >
          ikun 秒杀
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <Typography.Text type="secondary">
            高并发秒杀链路 + 旁路 AI 诊断 · 控制台
          </Typography.Text>
          {enabled && (
            <Space>
              <Avatar size="small" icon={<UserOutlined />} />
              <Typography.Text>{username ?? '未登录'}</Typography.Text>
              <Button
                size="small"
                onClick={() => {
                  clear()
                  navigate('/login', { replace: true })
                }}
              >
                退出
              </Button>
            </Space>
          )}
        </Header>
        <Content style={{ padding: 24 }}>
          <Suspense
            fallback={
              <div style={{ textAlign: 'center', paddingTop: 120 }}>
                <Spin size="large" />
              </div>
            }
          >
            <Outlet />
          </Suspense>
        </Content>
      </Layout>
    </Layout>
  )
}
