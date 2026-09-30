import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from '@/components/layout/AppLayout'
import ObservLayout from '@/components/layout/ObservLayout'
import ConversationPage from '@/pages/ConversationPage'
import SettingsPage from '@/pages/SettingsPage'
import LogsPage from '@/pages/LogsPage'
import TracePage from '@/pages/TracePage'

/**
 * 三区信息架构：
 *   - /chat/:sessionId?   生产区：AI 对话 + 终端/文件（默认首页）
 *   - /observability/*    回看区：操作日志 / Agent 追踪
 *   - /settings/:section? 设置居中弹框：通用 / 模型 / 主机 / 关于
 * 旧路径 /servers /logs /traces 全部 301 到新位置。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/chat" replace />} />
        <Route path="chat/:sessionId?" element={<ConversationPage />} />

        <Route path="observability" element={<ObservLayout />}>
          <Route index element={<Navigate to="logs" replace />} />
          <Route path="logs" element={<LogsPage />} />
          <Route path="traces" element={<TracePage />} />
        </Route>

        {/* 设置为居中弹框，分类仅由参数驱动（非法值在壳内回落到通用） */}
        <Route path="settings/:section?" element={<SettingsPage />} />

        {/* 旧路径兼容重定向 */}
        <Route path="servers" element={<Navigate to="/settings/hosts" replace />} />
        <Route path="logs" element={<Navigate to="/observability/logs" replace />} />
        <Route path="traces" element={<Navigate to="/observability/traces" replace />} />

        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Route>
    </Routes>
  )
}
