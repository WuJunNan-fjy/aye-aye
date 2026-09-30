import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, Search, Monitor, Settings, ArrowRight, Lock } from 'lucide-react'
import type { Host } from '@/api/hostApi'
import { useHostStore } from '@/store/useHostStore'
import { connectHost } from '@/lib/sshConnect'
import HostFormModal from './HostFormModal'

/**
 * 全局主机切换器（⌘K / Ctrl+K）：
 *   任意位置三步完成 搜索 → 选择 → 连接：
 *   - 已连接主机：选中即切换并进入对话；
 *   - 未连接主机：私钥直连；密码认证在同一浮层内第二步输入；
 *   - TOFU 指纹确认由 connectHost 统一承接；
 *   - ⌘N 添加新主机（弹 HostFormModal）。
 */
export default function HostSwitcher({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const hosts = useHostStore((s) => s.hosts)
  const loadHosts = useHostStore((s) => s.loadHosts)
  const selectHost = useHostStore((s) => s.selectHost)
  const serverStates = useHostStore((s) => s.serverStates)
  const currentHostId = useHostStore((s) => s.currentHostId)

  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [busyId, setBusyId] = useState<number | null>(null)
  /** 需要密码的主机（浮层内第二步） */
  const [pwHost, setPwHost] = useState<Host | null>(null)
  const [password, setPassword] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const pwRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setQuery(''); setActive(0); setPwHost(null); setPassword('')
      if (hosts.length === 0) void loadHosts().catch(() => {})
      setTimeout(() => inputRef.current?.focus(), 30)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  useEffect(() => { if (pwHost) setTimeout(() => pwRef.current?.focus(), 30) }, [pwHost])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = q
      ? hosts.filter((h) =>
          [h.name, h.address, h.username].some((v) => v.toLowerCase().includes(q)))
      : hosts
    // 已连接优先、其次当前选中
    return [...list].sort((a, b) => {
      const sa = a.id != null ? serverStates[a.id]?.status === 'connected' : false
      const sb = b.id != null ? serverStates[b.id]?.status === 'connected' : false
      if (sa !== sb) return sa ? -1 : 1
      if (a.id === currentHostId) return -1
      if (b.id === currentHostId) return 1
      return 0
    })
  }, [hosts, query, serverStates, currentHostId])

  const enterChat = useCallback((hostId: number) => {
    selectHost(hostId)
    navigate('/chat')
    onClose()
  }, [navigate, onClose, selectHost])

  const pick = useCallback(async (host: Host) => {
    if (host.id == null || busyId != null) return
    const status = serverStates[host.id]?.status
    if (status === 'connected') { enterChat(host.id); return }
    setBusyId(host.id)
    try {
      const r = await connectHost(host.id)
      if (r === 'connected') enterChat(host.id)
      else if (r === 'need_password') { setPwHost(host); setPassword('') }
    } finally {
      setBusyId(null)
    }
  }, [busyId, serverStates, enterChat])

  const submitPassword = useCallback(async () => {
    if (!pwHost?.id || !password) return
    setBusyId(pwHost.id)
    try {
      const r = await connectHost(pwHost.id, password)
      if (r === 'connected') enterChat(pwHost.id)
    } finally {
      setBusyId(null)
    }
  }, [pwHost, password, enterChat])

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (pwHost) {
      if (e.key === 'Enter') { e.preventDefault(); void submitPassword() }
      if (e.key === 'Escape') { setPwHost(null); setPassword('') }
      return
    }
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((i) => Math.min(i + 1, filtered.length - 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((i) => Math.max(i - 1, 0)) }
    else if (e.key === 'Enter') { e.preventDefault(); const h = filtered[active]; if (h) void pick(h) }
    else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'n') { e.preventDefault(); setFormOpen(true) }
  }

  if (!open) return null

  return (
    <div className="hs-overlay" onMouseDown={onClose}>
      <div className="hs-panel" onMouseDown={(e) => e.stopPropagation()}>
        {pwHost ? (
          <>
            <div className="hs-pw-head">
              <Lock className="hs-pw-lock" />
              <div className="hs-pw-title">连接 {pwHost.name}</div>
              <button type="button" className="hs-pw-back" onClick={() => { setPwHost(null); setPassword('') }}>返回</button>
            </div>
            <div className="hs-pw-meta">{pwHost.username}@{pwHost.address}:{pwHost.port}</div>
            <input
              ref={pwRef}
              type="password"
              className="dsh-input hs-pw-input"
              placeholder="输入 SSH 密码，回车连接"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={onKeyDown}
            />
            <div className="hs-footer">
              <span className="hs-hint">密码仅保存在内存中，关闭页面后失效</span>
              <button
                type="button"
                className="dsh-btn dsh-btn-primary dsh-btn-sm"
                disabled={busyId != null || !password}
                onClick={() => void submitPassword()}
              >
                {busyId != null ? '连接中…' : '连接'}
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="hs-search">
              <Search className="hs-search-icon" />
              <input
                ref={inputRef}
                className="hs-search-input"
                placeholder="搜索或连接主机…"
                value={query}
                onChange={(e) => { setQuery(e.target.value); setActive(0) }}
                onKeyDown={onKeyDown}
              />
            </div>
            <div className="hs-list">
              {filtered.length === 0 && (
                <div className="hs-empty">
                  <Monitor />
                  <span>{query ? '没有匹配的主机' : '还没有主机，按 ⌘N 添加第一台'}</span>
                </div>
              )}
              {filtered.map((h, i) => {
                const st = h.id != null ? serverStates[h.id] : undefined
                const connected = st?.status === 'connected'
                const busy = busyId === h.id
                return (
                  <div
                    key={h.id}
                    className={`hs-item ${i === active ? 'active' : ''}`}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => void pick(h)}
                  >
                    <span className={`hs-dot ${st?.status || 'disconnected'}`} />
                    <div className="hs-item-main">
                      <span className="hs-item-name">{h.name}</span>
                      <span className="hs-item-meta">{h.username}@{h.address}:{h.port} · {h.authType === 'PRIVATE_KEY' ? '私钥' : '密码'}</span>
                    </div>
                    {connected && <span className="hs-badge">已连接</span>}
                    {busy && <span className="hs-badge">连接中…</span>}
                    {st?.status === 'error' && <span className="hs-badge danger">异常</span>}
                    {h.id === currentHostId && !connected && <ArrowRight className="hs-current" />}
                  </div>
                )
              })}
            </div>
            <div className="hs-footer">
              <button type="button" className="hs-footer-action" onClick={() => setFormOpen(true)}>
                <Plus /> 添加新主机 <kbd>⌘N</kbd>
              </button>
              <button type="button" className="hs-footer-action" onClick={() => { navigate('/settings/hosts'); onClose() }}>
                <Settings /> 管理主机
              </button>
            </div>
          </>
        )}
      </div>

      <HostFormModal
        open={formOpen}
        editing={null}
        onClose={() => setFormOpen(false)}
        onSaved={(h) => { setQuery(''); if (h.id) setTimeout(() => setActive(Math.max(0, filtered.findIndex((x) => x.id === h.id))), 0) }}
      />
    </div>
  )
}
