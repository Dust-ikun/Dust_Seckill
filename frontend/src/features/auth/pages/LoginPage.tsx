import { useNavigate } from 'react-router-dom'
import { Button, Card, Form, Input, App } from 'antd'
import { useAuthStore } from '../store'

/**
 * 登录页（预留壳子）。
 * 当前无鉴权后端，提交仅写入本地 token 用于打通「登录 → 守卫 → 退出」链路；
 * 接入真实认证时，把 onSubmit 换成调用后端登录接口即可。
 */
export default function LoginPage() {
  const setToken = useAuthStore((s) => s.setToken)
  const navigate = useNavigate()
  const { message } = App.useApp()

  const onFinish = (values: { username: string; password: string }) => {
    // TODO: 替换为真实登录接口，换取后端签发的 token
    setToken(`local-${values.username}`, values.username)
    message.success('已进入（本地模拟登录）')
    navigate('/', { replace: true })
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card title="登录 · ikun 秒杀控制台" style={{ width: 360 }}>
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="admin" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="••••••" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
