import { useEffect, useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Minus, Square, X, Download } from 'lucide-react'
import { Modal, Progress } from 'antd'
import { useHostStore } from '@/store/useHostStore'
import type { ElectronBackendState, ElectronUpdateState } from '@/electron'
import AppSidebar from './AppSidebar'
import HostSwitcher from '@/components/servers/HostSwitcher'
import GlobalInteractionHost from '@/components/chat/GlobalInteractionHost'

/**
 * 应用外壳：
 *   可选 Frame Top（仅 Electron：窗口拖拽带 + 最小化/最大化/关闭）
 *   Frame Body：单一侧栏（宽 256 / 窄 56，可整体收起）+ 内容区
 * 侧栏品牌行、会话树、一级导航、主机卡、设置全部收进 AppSidebar。
 */
export default function AppLayout() {
  /** 全局主机切换器（⌘K / 侧栏主机卡共用）。 */
  const [switcherOpen, setSwitcherOpen] = useState(false)

  /**
   * 启动即恢复连接态：
   * 后端 SSH 会话存活期间（30min 空闲），刷新页面 / 重新打开标签页都能通过
   * GET /ssh/active 对账直接认领，无需重输密码；页面重新可见时再对账一次。
   */
  useEffect(() => {
    const reconcile = () => { void useHostStore.getState().reconcile() }
    reconcile()
    const onVisible = () => { if (!document.hidden) reconcile() }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  // Electron 窗口控制（纯 Web 运行时无 electronAPI → 不渲染顶栏）
  const [isElectron, setIsElectron] = useState(false)
  const [isMaximized, setIsMaximized] = useState(false)

  /**
   * 本地后端状态徽标（仅非 ready 时出现，尽可能不打扰）：
   * starting=脉动灰点 / degraded=琥珀点 / crashed|error=红点可点击重试。
   */
  const [backend, setBackend] = useState<ElectronBackendState | null>(null)
  useEffect(() => {
    const api = (window as any).electronAPI
    if (!api?.onBackendState) return
    api.getBackendState?.().then((s: ElectronBackendState) => setBackend(s)).catch(() => { /* ignore */ })
    const unsub = api.onBackendState((s: ElectronBackendState) => setBackend(s))
    return () => unsub?.()
  }, [isElectron])

  const backendBadge = (() => {
    if (!backend || backend.phase === 'ready' || backend.phase === 'stopped') return null
    if (backend.phase === 'starting') {
      return { cls: 'starting', label: '本地后端启动中', clickable: false }
    }
    if (backend.phase === 'degraded') {
      return { cls: 'degraded', label: '本地后端响应异常', clickable: false }
    }
    return { cls: 'down', label: '本地后端已断开 · 点击重试', clickable: true }
  })()

  /**
   * 应用自动更新：available/downloading/downloaded 时在标题栏出现墨绿徽标，
   * 点击打开弹窗（下载确认/进度/重启安装）；checking/error 等中间态不打扰。
   */
  const [update, setUpdate] = useState<ElectronUpdateState | null>(null)
  const [updateModalOpen, setUpdateModalOpen] = useState(false)
  useEffect(() => {
    const api = (window as any).electronAPI
    if (!isElectron || !api?.onUpdateState) return
    api.getUpdateState?.().then((s: ElectronUpdateState) => setUpdate(s)).catch(() => { /* ignore */ })
    const unsub = api.onUpdateState((s: ElectronUpdateState) => setUpdate(s))
    return () => unsub?.()
  }, [isElectron])

  const updateBadge = (() => {
    if (!update) return null
    if (update.status === 'available') {
      return { cls: 'available', label: `新版本 v${update.version ?? ''} · 点击更新` }
    }
    if (update.status === 'downloading') {
      return { cls: 'downloading', label: `下载更新 ${Math.round(update.progress)}%` }
    }
    if (update.status === 'downloaded') {
      return { cls: 'downloaded', label: `新版 v${update.version ?? ''} 已就绪 · 点击重启安装` }
    }
    return null
  })()

  const openUpdateModal = () => {
    const status = update?.status
    if (status === 'available' || status === 'downloading' || status === 'downloaded') {
      setUpdateModalOpen(true)
    }
  }

  useEffect(() => {
    const electronAPI = (window as any).electronAPI
    const electron = !!electronAPI?.minimizeWindow
    setIsElectron(electron)
    if (electron) {
      const check = async () => {
        try { setIsMaximized(!!(await electronAPI.isWindowMaximized())) } catch { /* ignore */ }
      }
      void check()
      window.addEventListener('resize', check)
      return () => window.removeEventListener('resize', check)
    }
  }, [])

  // 全局 ⌘K / Ctrl+K 唤起主机切换器；侧栏主机卡派发同名事件
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setSwitcherOpen(true)
      }
    }
    const onOpenEvent = () => setSwitcherOpen(true)
    window.addEventListener('keydown', onKey)
    window.addEventListener('aye:open-host-switcher', onOpenEvent)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('aye:open-host-switcher', onOpenEvent)
    }
  }, [])

  return (
    <div className="app-frame">
      {isElectron && (
        <header className="frame-top">
          {backendBadge && (
            backendBadge.clickable ? (
              <button
                type="button"
                className={`backend-badge ${backendBadge.cls}`}
                title="重新拉起本地后端"
                onClick={() => { void (window as any).electronAPI?.restartBackend?.() }}
              >
                <span className="backend-dot" />
                <span className="backend-label">{backendBadge.label}</span>
              </button>
            ) : (
              <div className={`backend-badge ${backendBadge.cls}`}>
                <span className="backend-dot" />
                <span className="backend-label">{backendBadge.label}</span>
              </div>
            )
          )}
          {updateBadge && (
            <button
              type="button"
              className={`update-badge ${updateBadge.cls}`}
              title="查看版本更新"
              onClick={openUpdateModal}
            >
              <Download size={12} />
              <span className="update-label">{updateBadge.label}</span>
            </button>
          )}
          <div className="frame-top-drag" />
          <div className="window-controls">
            <button className="wc-btn" title="最小化" onClick={() => (window as any).electronAPI?.minimizeWindow()}>
              <Minus />
            </button>
            <button className="wc-btn" title={isMaximized ? '还原' : '最大化'} onClick={() => (window as any).electronAPI?.maximizeWindow()}>
              <Square />
            </button>
            <button className="wc-btn close" title="关闭" onClick={() => (window as any).electronAPI?.closeWindow()}>
              <X />
            </button>
          </div>
        </header>
      )}

      <div className="frame-body">
        <AppSidebar />
        <main className="content-area">
          {/* TRAE 式浮层：灰色窗口底上的白色圆角内容面板（终端/对话/观测全包在内） */}
          <div className="content-surface">
            <Outlet />
          </div>
        </main>
      </div>

      <HostSwitcher open={switcherOpen} onClose={() => setSwitcherOpen(false)} />
      {/* 后台会话待决交互（审批/提问）浮层 + 后台异常结束通知；全路由可用 */}
      <GlobalInteractionHost />

      {/* 应用更新弹窗：确认下载 → 进度 → 重启安装（仅 Electron、有可交互更新态时存在） */}
      <Modal
        open={updateModalOpen && !!updateBadge}
        title={
          update?.status === 'downloaded'
            ? `新版本 v${update?.version ?? ''} 已就绪`
            : update?.status === 'downloading'
              ? '正在下载更新'
              : `发现新版本 v${update?.version ?? ''}`
        }
        centered
        width={420}
        maskClosable={false}
        onCancel={() => setUpdateModalOpen(false)}
        footer={
          update?.status === 'available'
            ? [
                <button
                  key="cancel"
                  type="button"
                  className="tm-update-btn ghost"
                  onClick={() => setUpdateModalOpen(false)}
                >
                  稍后提醒
                </button>,
                <button
                  key="ok"
                  type="button"
                  className="tm-update-btn primary"
                  onClick={() => { void (window as any).electronAPI?.downloadUpdate?.() }}
                >
                  立即下载
                </button>,
              ]
            : update?.status === 'downloading'
              ? [
                  <button
                    key="later"
                    type="button"
                    className="tm-update-btn ghost"
                    onClick={() => setUpdateModalOpen(false)}
                  >
                    后台下载
                  </button>,
                  <button key="wait" type="button" className="tm-update-btn primary" disabled>
                    下载中 {Math.round(update?.progress ?? 0)}%
                  </button>,
                ]
              : [
                  <button
                    key="cancel"
                    type="button"
                    className="tm-update-btn ghost"
                    onClick={() => setUpdateModalOpen(false)}
                  >
                    稍后重启
                  </button>,
                  <button
                    key="install"
                    type="button"
                    className="tm-update-btn primary"
                    onClick={() => { void (window as any).electronAPI?.quitAndInstallUpdate?.() }}
                  >
                    立即重启安装
                  </button>,
                ]
        }
      >
        {update?.notes ? (
          <div className="tm-update-notes">{update.notes}</div>
        ) : (
          <div className="tm-update-notes muted">
            {update?.status === 'downloaded'
              ? '安装将自动退出并重启 Aye，本地数据与连接配置不会丢失。'
              : '新版本将在退出并重启 Aye 后完成安装，本地数据不会丢失。'}
          </div>
        )}
        {update?.status === 'downloading' && (
          <Progress
            percent={Math.round(update.progress)}
            strokeColor={{ from: '#0f1115', to: '#0f1115' }}
            trailColor="rgba(0,0,0,0.08)"
            size="small"
            style={{ marginTop: 14 }}
          />
        )}
      </Modal>
    </div>
  )
}
