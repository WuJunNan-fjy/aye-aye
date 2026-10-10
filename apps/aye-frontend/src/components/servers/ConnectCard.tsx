import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plug, Plus, RotateCw } from 'lucide-react'
import { useHostStore } from '@/store/useHostStore'
import { connectHost } from '@/lib/sshConnect'

/**
 * 就地连接服务器卡片：终端面板 / 文件面板未连接时的空态。
 *
 * 替代旧的「去服务器页连接」跳转——不离开对话页即可：
 *  - 选择主机（多台时下拉，默认当前/上次选中的主机）
 *  - 输入密码（密码认证；私钥认证直接连）
 *  - 连接成功后宿主面板自动检测到 sessionId 并启用。
 */
export default function ConnectCard({ compact = false }: { compact?: boolean }) {
  const navigate = useNavigate()
  const hosts = useHostStore((s) => s.hosts)
  const currentHostId = useHostStore((s) => s.currentHostId)
  const selectHost = useHostStore((s) => s.selectHost)
  const serverStates = useHostStore((s) => s.serverStates)

  const [selectedId, setSelectedId] = useState<number | null>(
    currentHostId ?? hosts[0]?.id ?? null,
  )
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  // store 里的主机列表异步加载 / 外部选择变化时同步本地下拉。
  useEffect(() => {
    if (selectedId === null || !hosts.some((h) => h.id === selectedId)) {
      setSelectedId(currentHostId ?? hosts[0]?.id ?? null)
    }
  }, [hosts, currentHostId, selectedId])

  if (hosts.length === 0) {
    return (
      <div className="connect-card">
        <div className="connect-card-icon"><Plug /></div>
        <div className="connect-card-title">还没有可用的服务器</div>
        <div className="connect-card-desc">在「设置 → 主机」中添加一台服务器，之后即可在此直接连接。</div>
        <button type="button" className="dsh-btn dsh-btn-primary" onClick={() => navigate('/settings/hosts')}>
          <Plus /> 去添加主机
        </button>
      </div>
    )
  }

  const host = hosts.find((h) => h.id === selectedId) ?? hosts[0]
  const state = host?.id !== undefined ? serverStates[host.id] : undefined
  const status = state?.status ?? 'disconnected'
  const needPassword = host?.authType === 'PASSWORD'

  const doConnect = async () => {
    if (!host?.id || busy) return
    selectHost(host.id)
    setBusy(true)
    try {
      const r = await connectHost(host.id, needPassword ? password : undefined)
      if (r === 'need_password') setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`connect-card ${compact ? 'connect-card-compact' : ''}`}>
      <div className="connect-card-icon"><Plug /></div>
      <div className="connect-card-title">连接服务器</div>
      <div className="connect-card-desc">连接后终端与远程文件将自动启用，离开页面或刷新都不会中断（30 分钟空闲内有效）。</div>

      <div className="connect-card-form">
        {hosts.length > 1 && (
          <select
            className="dsh-input connect-card-select"
            value={host?.id ?? ''}
            onChange={(e) => {
              const id = Number(e.target.value)
              setSelectedId(id)
              selectHost(id)
            }}
          >
            {hosts.map((h) => (
              <option key={h.id} value={h.id}>
                {h.name}（{h.username}@{h.address}）
              </option>
            ))}
          </select>
        )}
        {needPassword && (
          <input
            className="dsh-input"
            type="password"
            placeholder="SSH 密码"
            value={password}
            disabled={busy}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void doConnect() }}
          />
        )}
        {status === 'error' && state?.error && (
          <div className="connect-card-error">{state.error}</div>
        )}
        <button
          type="button"
          className="dsh-btn dsh-btn-primary connect-card-btn"
          disabled={busy || (needPassword && password === '')}
          onClick={() => void doConnect()}
        >
          {busy ? <RotateCw className="dsh-spin" /> : <Plug />}
          {busy ? '连接中…' : '连接'}
        </button>
      </div>
    </div>
  )
}
