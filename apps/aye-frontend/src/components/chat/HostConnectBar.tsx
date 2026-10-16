/**
 * 输入条上方「当前主机未连接」轻量预警条（C 方案）。
 *
 * 发送前就提醒，避免 AI 跑进 Think 才发现主机不可用：
 *  - 无主机 → 引导去「设置 → 主机」添加；
 *  - 密码主机 → 先试缓存凭据，need_password 时条内展开密码小表单；
 *  - 已配置私钥路径的主机可被后端自动建连 → 不打扰（不显示）。
 * 连接成功后订阅 serverStates 自动消失。
 */
import { memo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { TriangleAlert, Plus, Plug, RotateCw } from 'lucide-react'
import { useHostStore } from '@/store/useHostStore'
import { connectHost } from '@/lib/sshConnect'

// 无 props，状态全部来自 host store：memo 后父页面流式重渲染完全不会波及本组件。
export const HostConnectBar = memo(function HostConnectBar({ showPrivateKey = false }: { showPrivateKey?: boolean }) {
  const navigate = useNavigate()
  const hosts = useHostStore((s) => s.hosts)
  const currentHostId = useHostStore((s) => s.currentHostId)
  const selectHost = useHostStore((s) => s.selectHost)

  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  const host = hosts.find((h) => h.id === currentHostId) ?? hosts[0]
  const hostId = host?.id
  const serverState = useHostStore((s) => (hostId != null ? s.serverStates[hostId] : undefined))

  // 无主机：添加引导。
  if (!host || host.id === undefined) {
    return (
      <div className="conv-hostbar">
        <span className="conv-hostbarIcon"><TriangleAlert /></span>
        <span className="conv-hostbarText">还没有配置主机，AI 将无法执行远程操作</span>
        <button
          type="button"
          className="dsh-btn dsh-btn-sm dsh-btn-primary conv-hostbarBtn"
          onClick={() => navigate('/settings/hosts')}
        >
          <Plus /> 添加主机
        </button>
      </div>
    )
  }

  const status = serverState?.status ?? 'disconnected'
  // 已连接 / 连接中瞬时态以外才提示；私钥路径主机可自动建连，不打扰。
  if (status === 'connected') return null
  const connecting = status === 'connecting' || busy
  if (!showPrivateKey && host.authType === 'PRIVATE_KEY' && host.privateKeyPath) return null

  const needPassword = host.authType === 'PASSWORD'

  const doConnect = async (pwd?: string): Promise<void> => {
    if (host.id === undefined || busy) return
    selectHost(host.id)
    setBusy(true)
    try {
      const r = await connectHost(host.id, pwd)
      if (r === 'need_password') {
        setOpen(true)
        setPassword('')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="conv-hostbar" data-open={open || undefined}>
      <div className="conv-hostbarRow">
        <span className="conv-hostbarIcon"><TriangleAlert /></span>
        <span className="conv-hostbarText">
          当前主机「{host.name}」未连接，AI 将无法执行远程操作
        </span>
        <button
          type="button"
          className="dsh-btn dsh-btn-sm dsh-btn-primary conv-hostbarBtn"
          disabled={connecting}
          onClick={() => void doConnect(needPassword ? password || undefined : undefined)}
        >
          {connecting ? <RotateCw className="dsh-spin" /> : <Plug />}
          {connecting ? '连接中…' : '连接'}
        </button>
      </div>
      {open && needPassword && (
        <div className="conv-hostbarForm">
          <input
            className="conv-hostbarPwd"
            type="password"
            name="tm-hostbar-password"
            autoComplete="off"
            aria-label="SSH 密码"
            spellCheck={false}
            placeholder="输入 SSH 密码后回车连接"
            value={password}
            disabled={connecting}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void doConnect(password || undefined) }}
          />
          {serverState?.status === 'error' && serverState.error && (
            <span className="conv-hostbarError">{serverState.error}</span>
          )}
        </div>
      )}
    </div>
  )
})
