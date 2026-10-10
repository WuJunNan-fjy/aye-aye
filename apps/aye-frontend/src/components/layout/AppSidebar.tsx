import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  MessageSquare,
  ChartLine,
  Settings,
  FileSearch,
  Server,
} from 'lucide-react'
import FxNavIcon from '@/components/fx/FxNavIcon'
import SessionTree from '@/components/chat/SessionTree'
import { useHostStore, selectCurrentHost, selectCurrentState } from '@/store/useHostStore'

/**
 * 应用侧栏 —— 对标 dsh ui-sidebar SidebarRoot 的「单栏双态」：
 *   宽态 256px：品牌行 / 一级切换（对话·观测）/ 新会话 / 区主体 + 底部主机卡 + 设置
 *   窄态 56px ：T 标（展开）/ 对话·观测图标 / 新会话 / 底部设置
 * 区主体随一级区切换：对话=会话树（按主机分组），观测/设置=二级菜单。
 * 设置固定沉底，不再占用左侧图标导航。
 */

const COLLAPSED_PREF = 'tm.sidebarCollapsed'
const WIDE_WIDTH = 256
const RAIL_WIDTH = 56
/* 收起第一拍：宽态内容淡出时长（宽度在此期间不动，文字不参与挤压） */
const CLOSE_FADE_MS = 130
/* 展开后保留 is-opening（内容延迟淡入动画）的时长 */
const OPEN_ANIM_MS = 280
const PREFERS_REDUCED =
  typeof window !== 'undefined'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches

const OBS_MENU = [
  { to: '/observability/logs', label: '操作日志', icon: <FileSearch /> },
  { to: '/observability/traces', label: 'Agent 追踪', icon: <ChartLine /> },
]

function statusText(status: string): string {
  const map: Record<string, string> = {
    connected: '已连接', connecting: '连接中', disconnected: '未连接', error: '连接异常',
  }
  return map[status] || status
}

function isPrefix(path: string, prefix: string): boolean {
  return path === prefix || path.startsWith(`${prefix}/`)
}

export default function AppSidebar() {
  const loc = useLocation()
  const navigate = useNavigate()
  const currentHost = useHostStore(selectCurrentHost)
  const currentState = useHostStore(selectCurrentState)
  const currentStatus = currentState?.status || 'disconnected'

  // 默认展开；开合状态持久化。
  // closing：收起第一拍（内容淡出、宽度不动）；opening：展开后内容延迟淡入。
  // 两拍拆开是为了让文字只做 opacity 过渡，不参与宽度收缩期的布局回流（挤压感根因）。
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem(COLLAPSED_PREF) === '1' } catch { return false }
  })
  const [closing, setClosing] = useState(false)
  const [opening, setOpening] = useState(false)
  const closeTimer = useRef<number | undefined>(undefined)
  const openTimer = useRef<number | undefined>(undefined)

  useEffect(() => () => {
    window.clearTimeout(closeTimer.current)
    window.clearTimeout(openTimer.current)
  }, [])

  const persist = (next: boolean) => {
    try { localStorage.setItem(COLLAPSED_PREF, next ? '1' : '0') } catch { /* ignore */ }
  }

  // 收起：先让宽态内容淡出（宽度保持 256，文字不被挤），再切宽度与窄态 DOM
  const collapse = () => {
    window.clearTimeout(closeTimer.current)
    window.clearTimeout(openTimer.current)
    setOpening(false)
    setClosing(true)
    closeTimer.current = window.setTimeout(() => {
      setClosing(false)
      setCollapsed(true)
      persist(true)
    }, PREFERS_REDUCED ? 0 : CLOSE_FADE_MS)
  }

  // 展开：立即切宽度（56→256 过渡）与宽态 DOM，内容由 CSS 延迟淡入
  const expand = () => {
    window.clearTimeout(closeTimer.current)
    setClosing(false)
    setCollapsed(false)
    persist(false)
    if (PREFERS_REDUCED) return
    window.clearTimeout(openTimer.current)
    setOpening(true)
    openTimer.current = window.setTimeout(() => setOpening(false), OPEN_ANIM_MS)
  }

  const toggle = () => (collapsed ? expand() : collapse())

  // 设置以弹框呈现，不占用侧栏主体；弹框打开后侧栏保留来源区上下文。
  const view: 'chat' | 'observability' = loc.pathname.startsWith('/observability') ? 'observability' : 'chat'
  const inSettings = loc.pathname.startsWith('/settings')

  const openSettings = () => {
    if (collapsed) expand()
    navigate('/settings/general', { state: { from: loc.pathname + loc.search } })
  }

  // 新会话：turn 已与页面解耦，运行中也可直接新开（后台 turn 继续执行）。
  const goChat = () => {
    navigate('/chat?new=1')
  }

  // 窄态点一级导航：导航并自动展开宽栏（dsh expandSidebar 语义）。
  const railGo = (to: string) => {
    if (collapsed) expand()
    navigate(to)
  }

  const renderMenuItems = (items: { to: string; label: string; icon: ReactNode }[]) => (
    <nav className="sb-menu">
      {items.map((m) => {
        const active = isPrefix(loc.pathname, m.to)
        return (
          <button
            key={m.to}
            type="button"
            className={`sb-menu-row ${active ? 'selected' : ''}`}
            onClick={() => navigate(m.to)}
          >
            <span className="st-slot sb-menu-icon">{m.icon}</span>
            <span className="st-title">{m.label}</span>
          </button>
        )
      })}
    </nav>
  )

  return (
    <aside
      className={`app-sidebar ${collapsed ? 'is-collapsed' : ''} ${closing ? 'is-closing' : ''} ${opening ? 'is-opening' : ''}`}
      style={{ width: collapsed ? RAIL_WIDTH : WIDE_WIDTH }}
    >
      {/* 品牌行：宽态 T+Aye + 折叠钮；窄态 T 标即展开钮（hover 露出面板图标） */}
      <div className="sb-logo-row">
        {!collapsed && (
          <div className="sb-brand">
            <svg className="sb-brand-mark" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <circle cx="12" cy="12.2" r="3.6" strokeWidth={1.6}/>
              <path d="M15.6 7.8 V16.6" strokeWidth={1.6} strokeLinecap="round"/>
              <circle className="aye-brand-pupil" cx="12" cy="12.2" r="1.2" fill="currentColor" stroke="none"/>
            </svg>
            <span className="sb-brand-name">Aye</span>
          </div>
        )}
        {collapsed ? (
          <button type="button" className="sb-rail-logo" title="展开侧栏" onClick={toggle}>
            <svg className="sb-brand-mark" width="1em" height="1em" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <circle cx="12" cy="12.2" r="3.6" strokeWidth={1.6}/>
              <path d="M15.6 7.8 V16.6" strokeWidth={1.6} strokeLinecap="round"/>
              <circle className="aye-brand-pupil" cx="12" cy="12.2" r="1.2" fill="currentColor" stroke="none"/>
            </svg>
            <span className="sb-rail-logo-hover">
              <FxNavIcon cls="pn-ico pn-open" className="sb-rail-logo-fx">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <rect width="18" height="18" x="3" y="3" rx="2" />
                  <path className="pn-rail" d="M9 3v18" />
                  <path className="pn-chv" d="m14 9 3 3-3 3" />
                </svg>
              </FxNavIcon>
            </span>
          </button>
        ) : (
          <button type="button" className="sb-icon-btn" title="收起侧栏" onClick={toggle}>
            <FxNavIcon cls="pn-ico pn-close" className="sb-icon-ico">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                <rect width="18" height="18" x="3" y="3" rx="2" />
                <path className="pn-rail" d="M9 3v18" />
                <path className="pn-chv" d="m16 15-3-3 3-3" />
              </svg>
            </FxNavIcon>
          </button>
        )}
      </div>

      {/* 区主体：一级切换（对话·观测）置顶、新会话紧随其后——
          对齐 Kimi/TRAE「模式切换在上、新建在下」的信息架构 */}
      <div className="sb-region">
        {collapsed ? (
          <div className="sb-rail-nav">
            <button
              type="button"
              className={`sb-rail-btn ${view === 'chat' ? 'active' : ''}`}
              title="对话"
              onClick={() => railGo('/chat')}
            >
              <MessageSquare />
            </button>
            <button
              type="button"
              className={`sb-rail-btn ${view === 'observability' ? 'active' : ''}`}
              title="观测"
              onClick={() => railGo('/observability/logs')}
            >
              <ChartLine />
            </button>
          </div>
        ) : (
          /* 一级切换：对话 / 观测 */
          <div className="sb-primary">
            <button
              type="button"
              className={`sb-primary-opt ${view === 'chat' ? 'active' : ''}`}
              onClick={() => navigate('/chat')}
            >
              <MessageSquare /> 对话
            </button>
            <button
              type="button"
              className={`sb-primary-opt ${view === 'observability' ? 'active' : ''}`}
              onClick={() => navigate('/observability/logs')}
            >
              <ChartLine /> 观测
            </button>
          </div>
        )}

        {/* 新会话：宽态 42px 灰底左对齐填充条（TRAE 新建任务同款），窄态 36px 纯图标；
            观测区也常驻（对齐 TRAE 新建任务常驻） */}
        <button type="button" className="sb-new-session" onClick={goChat}>
          <FxNavIcon cls="tm-nav tm-nav-1" className="sb-new-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
              <path className="nv-bubble" d="M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719" />
              <g className="nv-x">
                <path d="M8 12h8" />
                <path d="M12 8v8" />
              </g>
            </svg>
          </FxNavIcon>
          {!collapsed && <span className="sb-new-label">新会话</span>}
        </button>

        {!collapsed && view === 'chat' && <SessionTree />}
        {!collapsed && view === 'observability' && (
          <div className="sb-subnav">
            <div className="sb-subnav-label">观测中心</div>
            {renderMenuItems(OBS_MENU)}
          </div>
        )}
      </div>

      {/* 底部：主机切换（仅窄态常驻图标；宽态走会话树标题行 + Ctrl+K）+ 设置 */}
      <div className="sb-foot">
        {collapsed && (
          <button
            type="button"
            className="sb-rail-btn sb-rail-host"
            title={
              currentHost
                ? `${currentHost.name} · ${statusText(currentStatus)}（Ctrl+K 切换主机）`
                : '选择 / 连接主机（Ctrl+K）'
            }
            onClick={() => window.dispatchEvent(new CustomEvent('aye:open-host-switcher'))}
          >
            <Server />
            <span className={`sb-rail-host-dot ${currentStatus}`} />
          </button>
        )}
        {collapsed ? (
          <button
            type="button"
            className={`sb-rail-btn sb-rail-settings ${inSettings ? 'active' : ''}`}
            title="设置"
            onClick={openSettings}
          >
            <Settings />
          </button>
        ) : (
          <button
            type="button"
            className={`sb-settings-row ${inSettings ? 'selected' : ''}`}
            onClick={openSettings}
          >
            <span className="st-slot sb-menu-icon"><Settings /></span>
            <span className="st-title">设置</span>
          </button>
        )}
      </div>
    </aside>
  )
}
