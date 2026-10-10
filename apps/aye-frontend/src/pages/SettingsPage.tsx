import { useLocation, useNavigate, useParams } from 'react-router-dom'
import SettingsModal, { isSettingsSection, type SettingsSection } from '@/components/settings/SettingsModal'

/**
 * 设置中心路由壳：设置以居中弹框呈现（dsh SettingsRoot 形态）。
 * 路由 /settings/:section 只负责开合与当前分类；关闭时返回来源页（入口经 state.from 透传）。
 */
export default function SettingsPage() {
  const { section } = useParams()
  const navigate = useNavigate()
  const location = useLocation()

  const active: SettingsSection = isSettingsSection(section) ? section : 'general'
  const from = (location.state as { from?: string } | null)?.from
  const closeTo = from && !from.startsWith('/settings') ? from : '/chat'

  return (
    <SettingsModal
      section={active}
      onSelect={(id) => navigate(`/settings/${id}`, { replace: true, state: location.state })}
      onClose={() => navigate(closeTo, { replace: true })}
    />
  )
}
