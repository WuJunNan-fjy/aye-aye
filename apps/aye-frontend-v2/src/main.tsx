import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, HashRouter } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import App from './App'
import './styles/theme.css'
import './styles/primitives.css'
import './styles/layout.css'
import './styles/global.css'
import './styles/models.css'
import './styles/modelselect.css'
import './styles/message.css'
import './styles/inputbar.css'
import './styles/conversation.css'
import './styles/general.css'
import './styles/toolcard.css'
import './styles/fx-icons.css'
import './styles/servers.css'
import './styles/logs.css'
import './styles/sessions.css'
import './styles/shell.css'
import { applyTheme, getStoredTheme } from './lib/theme'
import { ensureBackend, watchBackendRestart } from './api/request'

// 应用启动时应用已保存的外观偏好（浅/深/跟随系统）。
applyTheme(getStoredTheme())

// 桌面模式下先解析后端随机端口与令牌，再渲染应用，避免首批请求打向默认 8080。
void ensureBackend()
// 监听崩溃自动重启后的 ready，刷新端口/令牌（仅桌面壳生效，浏览器模式为空操作）。
watchBackendRestart()

// antd v5 默认主色为 #1677ff 蓝，与项目黑白墨色体系冲突；此处将 antd 主色/错误色
// 对到 theme.css 同一套墨色/红色阶（浅：bluish-1000 #0F1115，深：bluish-50 #F9FAFB）。
// antd 未启用 darkAlgorithm，浅深取值通过监听 body[data-ds-dark-theme] 切换。
// 注意：Modal.confirm / message 等静态方法不消费此 context，由 shell.css 兜底覆写。
function AntdThemeBridge({ children }: { children: React.ReactNode }) {
  const [dark, setDark] = useState(() => document.body.hasAttribute('data-ds-dark-theme'))
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setDark(document.body.hasAttribute('data-ds-dark-theme'))
    })
    observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] })
    return () => observer.disconnect()
  }, [])
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: dark ? '#f9fafb' : '#0f1115',
          colorError: dark ? '#f25a5a' : '#ec1313',
          borderRadius: 8,
        },
        components: {
          Button: { primaryColor: dark ? '#0f1115' : '#ffffff' },
        },
      }}
    >
      {children}
    </ConfigProvider>
  )
}

// 生产桌面壳通过 file:// 加载 index.html。BrowserRouter 的 history.pushState('/chat')
// 在 file:// 下会被解析成磁盘根 file:///C:/chat，路由失配导致页面空白（导航看似点不动）。
// 因此 file: 协议用 HashRouter（路由进 #hash，刷新/深链安全）；http(s)（dev server/浏览器）
// 仍用 BrowserRouter 保持现有 URL 形态。
const HistoryRouter =
  typeof window !== 'undefined' && window.location.protocol === 'file:' ? HashRouter : BrowserRouter

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <HistoryRouter>
      <AntdThemeBridge>
        <App />
      </AntdThemeBridge>
    </HistoryRouter>
  </React.StrictMode>,
)
