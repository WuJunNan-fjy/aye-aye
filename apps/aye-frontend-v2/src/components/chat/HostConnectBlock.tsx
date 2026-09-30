/**
 * 主对话线「主机连接阻断卡」（B 方案）。
 *
 * 背景：SSH/SFTP 工具硬调失败时，后端只把"该主机尚未连接（凭据不落库…）"
 * /"主机密钥指纹尚未被信任"写进 Think 和工具错误文本里，主对话线没有任何引导。
 * 后端错误码不透传前端（ToolExecutor.makeErrorResult 只保留 message），
 * 因此这里按 SshToolSupport 的稳定中文文案识别阻断类型。
 *
 * 卡片内联在失败工具卡下方：输密码 → connectHost（含 TOFU 指纹确认弹窗）
 * → 订阅 serverStates 自动翻成成功态 →「继续执行」回调自动替用户发续跑消息。
 */
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  TriangleAlert, ShieldCheck, Plug, Plus,
  RotateCw, CircleCheck,
} from 'lucide-react'
import { useHostStore } from '@/store/useHostStore'
import { connectHost } from '@/lib/sshConnect'
import type { Host } from '@/api/hostApi'

export type ConnectBlockKind = 'not_connected' | 'key_not_trusted'

function asText(v: unknown): string {
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/** 识别连接类阻断（只匹配后端稳定中文文案，不尝试 parse 错误码）。 */
export function detectConnectBlock(record: { isError?: boolean; output?: unknown }): ConnectBlockKind | null {
  if (!record.isError) return null
  const text = asText(record.output)
  if (/密钥指纹尚未被信任|主机密钥.{0,12}(指纹|信任)/.test(text)) return 'key_not_trusted'
  if (/尚未连接|凭据不落库|请先在主机管理页连接/.test(text)) return 'not_connected'
  return null
}

function hostLabel(h: Host): string {
  const port = h.port ? `:${h.port}` : ''
  return `${h.name}（${h.username}@${h.address}${port}）`
}

export function HostConnectBlock({ kind, record, canResume = true, onResume }: {
  kind: ConnectBlockKind
  record: { input?: unknown }
  /** AI 是否空闲可接收续跑消息（运行中禁用「继续执行」）。 */
  canResume?: boolean
  onResume?: () => void
}) {
  const navigate = useNavigate()
  const hosts = useHostStore((s) => s.hosts)
  const currentHostId = useHostStore((s) => s.currentHostId)
  const selectHost = useHostStore((s) => s.selectHost)

  // 工具入参 host_key → host；找不到回退当前主机 / 第一台（与 ConnectCard 兜底一致）。
  const host = useMemo<Host | null>(() => {
    if (hosts.length === 0) return null
    const o = (record.input ?? {}) as Record<string, unknown>
    const key = typeof o.host_key === 'string' ? o.host_key : undefined
    if (key) {
      const hit = hosts.find((h) => h.hostKey === key)
      if (hit) return hit
    }
    return hosts.find((h) => h.id === currentHostId) ?? hosts[0] ?? null

  }, [hosts, currentHostId, record.input])

  const hostId = host?.id
  // 订阅该主机连接态：connectHost 成功后本卡自动翻成功态（ToolCard 是 memo，必须靠订阅触发）。
  const serverState = useHostStore((s) => (hostId != null ? s.serverStates[hostId] : undefined))

  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  // 无主机空态。
  if (!host || host.id === undefined) {
    return (
      <div className="hcb-root" data-state="empty">
        <div className="hcb-head">
          <span className="hcb-icon"><TriangleAlert /></span>
          <span className="hcb-title">还没有配置主机</span>
        </div>
        <div className="hcb-desc">AI 需要先连接一台主机，才能代执行远程操作。</div>
        <div className="hcb-actions">
          <button
            type="button"
            className="dsh-btn dsh-btn-sm dsh-btn-primary hcb-btn"
            onClick={() => navigate('/settings/hosts')}
          >
            <Plus /> 去添加主机
          </button>
        </div>
      </div>
    )
  }

  const connected = serverState?.status === 'connected'
  const connecting = serverState?.status === 'connecting' || busy
  const needPassword = host.authType === 'PASSWORD'

  const doConnect = async (): Promise<void> => {
    if (host.id === undefined || busy) return
    selectHost(host.id)
    setBusy(true)
    try {
      const r = await connectHost(host.id, needPassword ? password || undefined : undefined)
      if (r === 'need_password') setPassword('')
    } finally {
      setBusy(false)
    }
  }

  // 成功态：引导续跑被中断的任务。
  if (connected) {
    return (
      <div className="hcb-root" data-state="connected">
        <div className="hcb-head">
          <span className="hcb-icon hcb-iconOk"><CircleCheck /></span>
          <span className="hcb-title">已连接 {host.name}</span>
        </div>
        <div className="hcb-desc">连接已就绪，可以让 AI 继续刚才被中断的任务。</div>
        <div className="hcb-actions">
          <button
            type="button"
            className="dsh-btn dsh-btn-sm dsh-btn-primary hcb-btn"
            disabled={!canResume}
            title={canResume ? undefined : 'AI 正在响应中，请稍候'}
            onClick={() => onResume?.()}
          >
            继续执行
          </button>
        </div>
      </div>
    )
  }

  const isTrust = kind === 'key_not_trusted'

  return (
    <div className="hcb-root" data-state={isTrust ? 'trust' : 'block'}>
      <div className="hcb-head">
        <span className="hcb-icon">{isTrust ? <ShieldCheck /> : <TriangleAlert />}</span>
        <span className="hcb-title">{isTrust ? '主机密钥指纹尚未信任' : '需要先连接主机'}</span>
      </div>
      <div className="hcb-host">{hostLabel(host)}</div>
      <div className="hcb-desc">
        {isTrust
          ? '首次连接需确认主机密钥指纹：点击连接后会弹出指纹确认框，核对无误后选择信任即可。'
          : 'SSH 凭据仅保存在内存中、不落库，请先在对话内连接一次，AI 才能代执行远程命令。'}
      </div>
      <div className="hcb-form">
        {needPassword && (
          <input
            className="hcb-pwd"
            type="password"
            name="tm-hcb-password"
            autoComplete="off"
            aria-label="SSH 密码"
            spellCheck={false}
            placeholder="输入 SSH 密码"
            value={password}
            disabled={connecting}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void doConnect() }}
          />
        )}
        <button
          type="button"
          className="dsh-btn dsh-btn-sm dsh-btn-primary hcb-btn"
          disabled={connecting || (needPassword && password === '')}
          onClick={() => void doConnect()}
        >
          {connecting ? <RotateCw className="dsh-spin" /> : <Plug />}
          {connecting ? '连接中…' : '连接主机'}
        </button>
      </div>
      {serverState?.status === 'error' && serverState.error && (
        <div className="hcb-error">{serverState.error}</div>
      )}
    </div>
  )
}
