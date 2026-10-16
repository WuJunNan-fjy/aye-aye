import { Routes, Route, Navigate } from 'react-router-dom'
import AppLayout from '@/components/layout/AppLayout'
import ObservLayout from '@/components/layout/ObservLayout'
import ConversationPage from '@/pages/ConversationPage'
import SettingsPage from '@/pages/SettingsPage'
import LogsPage from '@/pages/LogsPage'
import TracePage from '@/pages/TracePage'
import ProjectsPage from '@/pages/ProjectsPage'

/**
 * 项目首页 → 部署任务；诊断与设置作为辅助入口。旧路径保留客户端重定向。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<AppLayout />}>
        <Route index element={<Navigate to="/projects" replace />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="chat" element={<Navigate to="/projects" replace />} />
        <Route path="chat/:sessionId" element={<ConversationPage />} />

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

        <Route path="*" element={<Navigate to="/projects" replace />} />
      </Route>
    </Routes>
  )
}
