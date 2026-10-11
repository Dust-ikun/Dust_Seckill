import type { ThemeConfig } from 'antd'

/**
 * 设计 token 单点管理：换肤/暗色模式只改这里。
 * 需要暗色时包一层 theme.darkAlgorithm 即可，业务组件不用动。
 */
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 6,
  },
}
