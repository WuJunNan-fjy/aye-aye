import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Folder,
  FolderOpen,
  ChevronRight,
  Plus,
  Ellipsis,
  RotateCw,
  Search,
  X,
  Plug,
  Unplug,
  Settings,
  Server,
} from 'lucide-react'
import { Dropdown, Modal, message } from 'antd'
import type { SessionInfo } from '@/api/sessionsApi'
import { useHostStore, selectCurrentHost } from '@/store/useHostStore'
import { useSessionsStore } from '@/store/useSessionsStore'
import { useRunningSessionIds, destroySessionRuntime } from '@/store/useSessionRuntime'
import { getLastSessionId, setLastSessionId } from '@/lib/prefs'

/**
 * 侧栏会话树 —— 结构 1:1 对标 dsh ui-workspace：
 *   ProjectRow（主机组：folder/chevron 槽 + 主机名 + hover 露出 …/+）
 *   SessionNode（会话行：16px 状态槽 + 标题 + 时间，hover 换出删除钮）
 *   SearchResult（搜索扁平行：标题 + 主机名 meta）
 * 一台主机 = dsh 一个工作区；hostId 为空的会话进入「未分组」桶。
 */

const COLLAPSED_PREF = 'tm.hostGroupCollapsed'
const UNGROUPED = '__ungrouped__'
const RUN_LIMIT = 5

function loadCollapsed(): Set<string> {
  try {
    const raw = localStorage.getItem(COLLAPSED_PREF)
    if (raw) return new Set(JSON.parse(raw) as string[])
  } catch { /* ignore */ }
  return new Set()
}

function timeLabel(ts?: number | null): string {
  if (ts == null || ts <= 0) return ''
  // 兼容秒级 / 毫秒级 epoch
  const ms = ts < 1e12 ? ts * 1000 : ts
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay(d, now)) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (sameDay(d, yesterday)) return '昨天'
  const diffDays = Math.floor((now.getTime() - d.getTime()) / 86_400_000)
  if (diffDays < 30) return `${diffDays}天前`
  if (d.getFullYear() === now.getFullYear()) return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

interface HostGroup {
  key: string
  hostId: number | null
  label: string
  sessions: SessionInfo[]
}

/** 唤起全局主机切换器（Ctrl+K 浮层），侧栏内各入口共用。 */
function openHostSwitcher() {
  window.dispatchEvent(new CustomEvent('aye:open-host-switcher'))
}

export default function SessionTree() {
  const navigate = useNavigate()
  const params = useParams()
  const activeId = params.sessionId ?? null

  const hosts = useHostStore((s) => s.hosts)
  const currentHost = useHostStore(selectCurrentHost)
  const selectHost = useHostStore((s) => s.selectHost)
  const disconnect = useHostStore((s) => s.disconnect)

  const sessions = useSessionsStore((s) => s.sessions)
  const loading = useSessionsStore((s) => s.loading)
  const loadSessions = useSessionsStore((s) => s.load)
  const removeSession = useSessionsStore((s) => s.remove)
  // 运行指示 = 本标签 runtime 实时态 ∪ 列表 runningTurn（冷启动/其他标签发起的 turn）。
  const runIds = useRunningSessionIds()

  const [query, setQuery] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [collapsed, setCollapsed] = useState<Set<string>>(loadCollapsed)
  const [runsOpen, setRunsOpen] = useState<Set<string>>(new Set())
  const searchInputRef = useRef<HTMLInputElement | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [debouncedQuery, setDebouncedQuery] = useState('')

  // 首次挂载加载；可见性变化时静默刷新（标题/更新时间对账）。
  useEffect(() => {
    void loadSessions()
    const onVisible = () => { if (!document.hidden) void loadSessions(true) }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [loadSessions])

  // 当前会话所在组自动展开（不覆盖用户显式折叠的持久化意图之外的当前定位）。
  useEffect(() => {
    if (!activeId) return
    const owner = sessions.find((s) => s.id === activeId)
    const key = owner?.hostId != null ? String(owner.hostId) : UNGROUPED
    setCollapsed((prev) => {
      if (!prev.has(key)) return prev
      const next = new Set(prev)
      next.delete(key)
      return next
    })
  }, [activeId, sessions])

  const persistCollapsed = useCallback((next: Set<string>) => {
    setCollapsed(next)
    try { localStorage.setItem(COLLAPSED_PREF, JSON.stringify([...next])) } catch { /* ignore */ }
  }, [])

  const toggleGroup = (key: string) => {
    const next = new Set(collapsed)
    if (next.has(key)) next.delete(key); else next.add(key)
    persistCollapsed(next)
  }

  const onQueryChange = (v: string) => {
    setQuery(v)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => setDebouncedQuery(v.trim().toLowerCase()), 180)
  }

  const newSession = useCallback((hostId: number | null) => {
    if (hostId != null) selectHost(hostId)
    navigate('/chat?new=1')
  }, [navigate, selectHost])

  const openSession = useCallback((id: string) => {
    setLastSessionId(id)
    navigate(`/chat/${id}`)
  }, [navigate])

  const deleteSession = useCallback(async (s: SessionInfo) => {
    try {
      await removeSession(s.id)
      destroySessionRuntime(s.id)
      if (getLastSessionId() === s.id) setLastSessionId(null)
      if (s.id === activeId) navigate('/chat?new=1')
    } catch {
      message.error('删除会话失败')
    }
  }, [activeId, navigate, removeSession])

  /** 正在运行 turn 的会话 id 集合（实时 runtime + 服务端列表 runningTurn 合并去重）。 */
  const runningSet = useMemo(() => {
    const set = new Set(runIds)
    for (const s of sessions) {
      if (s.runningTurn != null) set.add(s.id)
    }
    return set
  }, [runIds, sessions])

  // 主机分组（保持主机列表顺序），未分组桶置底。
  const groups = useMemo<HostGroup[]>(() => {
    const byHost = new Map<number, SessionInfo[]>()
    const loose: SessionInfo[] = []
    for (const s of sessions) {
      if (s.hostId != null) {
        const arr = byHost.get(s.hostId) ?? []
        arr.push(s)
        byHost.set(s.hostId, arr)
      } else {
        loose.push(s)
      }
    }
    const list: HostGroup[] = hosts.map((h) => ({
      key: String(h.id),
      hostId: h.id ?? null,
      label: h.name,
      sessions: (byHost.get(h.id ?? -1) ?? []).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)),
    })).filter((g) => g.hostId != null)
    if (loose.length > 0) {
      list.push({
        key: UNGROUPED,
        hostId: null,
        label: '未分组',
        sessions: loose.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)),
      })
    }
    return list
  }, [hosts, sessions])

  const hostNameOf = useCallback((hostId?: number | null) => {
    if (hostId == null) return '未分组'
    return hosts.find((h) => h.id === hostId)?.name ?? `主机 #${hostId}`
  }, [hosts])

  const searchResults = useMemo(() => {
    if (!debouncedQuery) return []
    return sessions
      .filter((s) => (s.title || '').toLowerCase().includes(debouncedQuery))
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
  }, [debouncedQuery, sessions])

  const statusOf = (hostId: number | null) =>
    hostId != null ? (useHostStore.getState().serverStates[hostId]?.status ?? 'disconnected') : 'disconnected'

  // ── 搜索扁平结果（dsh SearchResultItem）──
  if (debouncedQuery) {
    return (
      <div className="st-root">
        <div className={`st-header ${searchOpen ? 'search-open' : ''}`}>
          <div className={`st-search ${searchOpen ? 'expanded' : ''}`}>
            <button
              type="button"
              className="st-search-btn"
              onClick={() => { setSearchOpen(true); setTimeout(() => searchInputRef.current?.focus(), 0) }}
            >
              <Search />
            </button>
            <input
              ref={searchInputRef}
              type="text"
              name="tm-session-search"
              autoComplete="off"
              aria-label="搜索会话"
              spellCheck={false}
              className="st-search-input"
              placeholder="搜索会话"
              value={query}
              onChange={(e) => onQueryChange(e.target.value)}
            />
            {query && (
              <button type="button" className="st-clear-btn" onClick={() => { onQueryChange(''); setQuery('') }}>
                <X />
              </button>
            )}
          </div>
        </div>
        <div className="st-list">
          {searchResults.length === 0 && <div className="st-empty">没有匹配的会话</div>}
          {searchResults.map((s) => (
            <button
              key={s.id}
              type="button"
              className={`st-search-row ${s.id === activeId ? 'selected' : ''}`}
              onClick={() => openSession(s.id)}
            >
              <span className="st-search-heading">
                <span className="st-slot">
                  {runningSet.has(s.id) && <span className="st-run-dot" title="任务运行中" />}
                </span>
                <span className="st-search-title">{s.title || '新会话'}</span>
              </span>
              <span className="st-search-meta">
                <span className="st-search-host">{hostNameOf(s.hostId)}</span>
                <span className="st-search-time">{timeLabel(s.updatedAt)}</span>
              </span>
            </button>
          ))}
        </div>
      </div>
    )
  }

  return (
    <div className="st-root">
      {/* 区段头：标题 + 展开式搜索 + 刷新（dsh sectionHeader） */}
      <div className={`st-header ${searchOpen ? 'search-open' : ''}`}>
        <span className={`st-section-label ${searchOpen ? 'hidden' : ''}`}>会话</span>
        <div className={`st-search ${searchOpen ? 'expanded' : ''}`}>
          <button
            type="button"
            className="st-search-btn"
            onClick={() => { setSearchOpen(true); setTimeout(() => searchInputRef.current?.focus(), 0) }}
          >
            <Search />
          </button>
          {/* 仅展开时挂载，避免浏览器把常驻输入框识别为用户名并自动填充 */}
          {searchOpen && (
            <input
              ref={searchInputRef}
              type="text"
              name="tm-session-search"
              autoComplete="off"
              aria-label="搜索会话"
              spellCheck={false}
              className="st-search-input"
              placeholder="搜索会话"
              value={query}
              onChange={(e) => onQueryChange(e.target.value)}
            />
          )}
          {query && (
            <button type="button" className="st-clear-btn" onClick={() => { onQueryChange(''); setQuery('') }}>
              <X />
            </button>
          )}
        </div>
        {!searchOpen && (
          <div className="st-header-actions">
            <button
              type="button"
              className="st-icon-btn"
              title="切换 / 连接主机（Ctrl+K）"
              onClick={openHostSwitcher}
            >
              <Server />
            </button>
            <button type="button" className="st-icon-btn" title="刷新会话" onClick={() => void loadSessions(true)}>
              <RotateCw className={loading ? 'dsh-spin' : undefined} />
            </button>
          </div>
        )}
      </div>

      <div className="st-list">
        {groups.length === 0 && !loading && <div className="st-empty">暂无会话，从上方「新会话」开始</div>}

        {groups.map((g) => {
          const isCollapsed = collapsed.has(g.key)
          const isActiveHost = g.hostId != null && currentHost?.id === g.hostId
          const expandedRun = runsOpen.has(g.key)
          const shownSessions = expandedRun ? g.sessions : g.sessions.slice(0, RUN_LIMIT)
          const overflow = g.sessions.length - RUN_LIMIT
          const hostStatus = statusOf(g.hostId)

          const menuItems = g.hostId == null ? [] : [
            hostStatus === 'connected'
              ? { key: 'disconnect', icon: <Unplug />, label: '断开连接', onClick: () => void disconnect(g.hostId!) }
              : {
                  key: 'connect',
                  icon: <Plug />,
                  label: hostStatus === 'connecting' ? '连接中…' : '连接主机',
                  disabled: hostStatus === 'connecting',
                  onClick: () => {
                    selectHost(g.hostId!)
                    openHostSwitcher()
                  },
                },
            {
              key: 'manage',
              icon: <Settings />,
              label: '主机设置',
              onClick: () => navigate('/settings/hosts'),
            },
          ]

          return (
            <div key={g.key} className="st-group">
              {/* 主机组头（dsh ProjectRow 34px：folder 槽 hover 换 chevron + …/+） */}
              <div className="st-project-row" onClick={() => toggleGroup(g.key)}>
                <span className={`st-slot st-folder ${isActiveHost ? 'folder-active' : ''}`}>
                  {isCollapsed ? <Folder /> : <FolderOpen />}
                </span>
                <span className="st-slot st-chevron">
                  <ChevronRight className={`st-arrow ${isCollapsed ? '' : 'open'}`} />
                </span>
                <span className="st-project-text">
                  <span className="st-title">{g.label}</span>
                </span>
                {g.hostId != null && <span className={`st-host-dot ${hostStatus}`} />}
                <span className="st-count">{g.sessions.length}</span>
                <span className="st-row-actions">
                  {menuItems.length > 0 && (
                    <Dropdown
                      trigger={['click']}
                      menu={{ items: menuItems }}
                      placement="bottomRight"
                    >
                      <button
                        type="button"
                        className="st-mini-btn tm-fx-dots"
                        title="主机操作"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Ellipsis />
                      </button>
                    </Dropdown>
                  )}
                  <button
                    type="button"
                    className="st-mini-btn tm-fx-wobble"
                    title="在该主机上新会话"
                    onClick={(e) => { e.stopPropagation(); newSession(g.hostId) }}
                  >
                    <Plus />
                  </button>
                </span>
              </div>

              {/* 会话 run */}
              {!isCollapsed && (
                <>
                  {shownSessions.map((s) => (
                    <div
                      key={s.id}
                      className={`st-session-row ${s.id === activeId ? 'selected' : ''}`}
                      onClick={() => openSession(s.id)}
                    >
                      <span className="st-slot">
                        {runningSet.has(s.id) && <span className="st-run-dot" title="任务运行中" />}
                      </span>
                      <span className="st-title">{s.title || '新会话'}</span>
                      <span className="st-time">{timeLabel(s.updatedAt)}</span>
                      <span className="st-row-actions">
                        <Dropdown
                          trigger={['click']}
                          placement="bottomRight"
                          menu={{
                            items: [
                              {
                                key: 'delete',
                                label: '删除会话',
                                danger: true,
                                onClick: () => {
                                  Modal.confirm({
                                    title: '删除该会话？',
                                    content: '会话内的消息与操作记录将不可恢复。',
                                    okText: '删除',
                                    cancelText: '取消',
                                    okButtonProps: { danger: true },
                                    onOk: () => deleteSession(s),
                                  })
                                },
                              },
                            ],
                          }}
                        >
                          <button
                            type="button"
                            className="st-mini-btn tm-fx-dots"
                            title="会话操作"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Ellipsis />
                          </button>
                        </Dropdown>
                      </span>
                    </div>
                  ))}
                  {g.sessions.length === 0 && (
                    <button
                      type="button"
                      className="st-group-empty"
                      onClick={() => g.hostId != null && newSession(g.hostId)}
                    >
                      {g.hostId != null ? '在该主机开始新会话' : '暂无会话'}
                    </button>
                  )}
                  {g.sessions.length > RUN_LIMIT && (
                    <button
                      type="button"
                      className="st-overflow-btn"
                      onClick={() => {
                        const next = new Set(runsOpen)
                        if (next.has(g.key)) next.delete(g.key); else next.add(g.key)
                        setRunsOpen(next)
                      }}
                    >
                      {expandedRun ? '收起' : `展开剩余 ${overflow} 条`}
                    </button>
                  )}
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
