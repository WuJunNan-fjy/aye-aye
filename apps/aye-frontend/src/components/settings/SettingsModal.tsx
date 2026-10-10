import { useEffect, useRef, type ReactNode } from 'react'
import {
  X,
  SlidersHorizontal,
  Boxes,
  Monitor,
  Info,
} from 'lucide-react'
import SettingsGeneralPage from '@/pages/settings/SettingsGeneralPage'
import SettingsModelsPage from '@/pages/settings/SettingsModelsPage'
import SettingsHostsPage from '@/pages/settings/SettingsHostsPage'
import SettingsAboutPage from '@/pages/settings/SettingsAboutPage'

/**
 * 设置弹框 —— 1:1 复刻 dsh ui-settings-general/SettingsRoot：
 * 全屏遮罩 + 居中 800px 面板（左 188px 分类导航 / 右内容列 54px 头 + 滚动 options）。
 * 关闭路径：右上关闭钮、点击遮罩、Escape。开合与当前分类由 /settings/:section 路由驱动。
 */

export type SettingsSection = 'general' | 'models' | 'hosts' | 'about'

export const SETTINGS_SECTIONS: ReadonlyArray<{ id: SettingsSection; label: string; icon: ReactNode }> = [
  { id: 'general', label: '通用', icon: <SlidersHorizontal /> },
  { id: 'models', label: '模型', icon: <Boxes /> },
  { id: 'hosts', label: '主机', icon: <Monitor /> },
  { id: 'about', label: '关于', icon: <Info /> },
]

export function isSettingsSection(v: string | undefined): v is SettingsSection {
  return !!v && SETTINGS_SECTIONS.some((s) => s.id === v)
}

type Props = {
  section: SettingsSection
  onSelect: (id: SettingsSection) => void
  onClose: () => void
}

export default function SettingsModal({ section, onSelect, onClose }: Props) {
  // 进入弹框焦点落到关闭钮；Esc 关闭（组件仅在 /settings 路由激活时挂载）。
  const closeButton = useRef<HTMLButtonElement | null>(null)
  useEffect(() => { closeButton.current?.focus() }, [])
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div className="sg-overlay" role="presentation">
      <div className="sg-mask" aria-hidden="true" onClick={onClose} />
      <div
        className="sg-panel"
        role="dialog"
        aria-modal="true"
        aria-label="设置"
      >
        <nav className="sg-nav">
          <div className="sg-nav-title">设置</div>
          <div className="sg-nav-list">
            {SETTINGS_SECTIONS.map((row) => (
              <button
                key={row.id}
                type="button"
                className={`sg-nav-cell ${row.id === section ? 'active' : ''}`}
                aria-current={row.id === section ? 'true' : undefined}
                onClick={() => onSelect(row.id)}
              >
                <span className="sg-nav-icon">{row.icon}</span>
                <span className="sg-nav-label">{row.label}</span>
              </button>
            ))}
          </div>
        </nav>
        <div className="sg-content">
          <div className="sg-header">
            <button ref={closeButton} type="button" className="sg-close" onClick={onClose} aria-label="关闭设置">
              <X />
            </button>
          </div>
          <div className="sg-options">
            {section === 'general' && <SettingsGeneralPage />}
            {section === 'models' && <SettingsModelsPage />}
            {section === 'hosts' && <SettingsHostsPage />}
            {section === 'about' && <SettingsAboutPage />}
          </div>
        </div>
      </div>
    </div>
  )
}
