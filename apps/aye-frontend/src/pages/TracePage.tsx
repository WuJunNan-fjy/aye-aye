import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  RotateCw,
  Search,
  MessageSquare,
  ChartLine,
  Radio,
  Pause,
  HelpCircle,
  Archive,
  AlertTriangle,
  ChevronRight,
  Check,
  X as XIcon,
  ShieldCheck,
  ShieldX,
  ShieldQuestion,
  Loader2,
  PauseCircle,
  Server,
  SquareTerminal as TerminalIcon,
} from 'lucide-react'
import { Drawer } from 'antd'
import obsApi, {
  type SessionRow,
  type TurnState,
  type TrajectoryResult,
  type TrajectoryRow,
  type ToolDetail,
} from '@/api/observabilityApi'
import { getToolLabel } from '@/api/auditApi'
import '@/styles/observability.css'

/** turn 账本自动刷新间隔（ms）。 */
const AUTO_REFRESH_MS = 10_000
const PAGE_SIZE = 50

/** 语义色调统一映射 token class（ok/warn/danger/neutral），禁止行内硬编码色值。 */
type Tone = 'ok' | 'warn' | 'danger' | 'neutral'

const TURN_STATE_META: Record<TurnState, { label: string; tone: Tone }> = {
  running: { label: '运行中', tone: 'neutral' },
  completed: { label: '完成', tone: 'ok' },
  error: { label: '异常', tone: 'danger' },
  aborted: { label: '已停止', tone: 'neutral' },
  blocked: { label: '已阻断', tone: 'warn' },
  max_tokens: { label: '长度截断', tone: 'warn' },
  interrupted: { label: '中断恢复', tone: 'warn' },
}

const MODE_LABEL: Record<string, string> = {
  readonly: '只读',
  write_confirm: '写确认',
  all_confirm: '全确认',
}

function pad(n: number): string {
  return n.toString().padStart(2, '0')
}

function formatMs(ms?: number | null, withDate = true): string {
  if (!ms) return '-'
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return '-'
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return withDate ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}` : time
}

function formatDuration(ms?: number | null): string {
  if (ms === undefined || ms === null || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m${Math.floor((ms % 60000) / 1000)}s`
}

function formatRelative(ms?: number | null): string {
  if (!ms) return '-'
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return formatMs(ms)
}

function shortSession(id: string): string {
  const a = id.split('-')
  return a.length > 1 ? `${a[0]}…${a[a.length - 1].slice(0, 6)}` : id.slice(0, 10)
}

function tokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k`
  return String(n)
}

function StateDot({ state, size = 8 }: { state: TurnState; size?: number }) {
  const meta = TURN_STATE_META[state] ?? TURN_STATE_META.completed
  return (
    <span
      className={`obs-state-dot tone-${meta.tone} ${state === 'running' ? 'live' : ''}`}
      style={{ width: size, height: size }}
      title={meta.label}
    />
  )
}

// ============================================================
// trajectory 时间线（两级阅读：turn 摘要行 → 展开消息/命令）
// 设计原则：成功只留小绿勾，红色仅属于失败；模型请求/指标等内部信息
// 不单独成卡；命令默认 38px 单行、输出全部折叠。
// ============================================================

/** 内部准备类工具（列主机 / 动态加载工具 / 任务清单）：不占命令序号，相邻多条合并为一行。 */
const PREP_TOOLS = new Set(['list_hosts', 'tool_search', 'todo_write'])

type TurnStartRow = Extract<TrajectoryRow, { kind: 'turn-start' }>
type TurnEndRow = Extract<TrajectoryRow, { kind: 'turn-end' }>
type ToolRow = Extract<TrajectoryRow, { kind: 'tool' }>
type AssistantRow = Extract<TrajectoryRow, { kind: 'assistant' }>
type SignificantRow = Exclude<
  TrajectoryRow,
  { kind: 'turn-start' | 'turn-end' | 'step-start' | 'step-end' }
>

interface TurnGroup {
  turnNo: number
  start?: TurnStartRow
  end?: TurnEndRow
  items: SignificantRow[]
}

/** 按 turnNo 聚合事件流（Map 保持首次出现顺序）。 */
function groupTurns(rows: TrajectoryRow[]): TurnGroup[] {
  const map = new Map<number, TurnGroup>()
  for (const r of rows) {
    let g = map.get(r.turnNo)
    if (!g) {
      g = { turnNo: r.turnNo, items: [] }
      map.set(r.turnNo, g)
    }
    if (r.kind === 'turn-start') g.start = r
    else if (r.kind === 'turn-end') g.end = r
    else if (r.kind !== 'step-start' && r.kind !== 'step-end') g.items.push(r)
  }
  return [...map.values()]
}

function previewArgs(args: unknown): string {
  if (args == null) return ''
  if (typeof args === 'string') return args
  try {
    return JSON.stringify(args)
  } catch {
    return String(args)
  }
}

function ToolStatusIcon({ status }: { status: ToolDetail['status'] }) {
  if (status === 'success') return <Check size={13} className="tj-ico-ok" />
  if (status === 'failed') return <XIcon size={13} className="tj-ico-err" />
  if (status === 'interrupted') return <PauseCircle size={13} className="tj-ico-warn" />
  return <Loader2 size={13} className="tj-ico-run dsh-spin" />
}

/** 审批状态收成一枚小盾牌（自动执行不显示），语义靠 hover 提示补充，不再刷彩色文字 badge。 */
function ApprovalIcon({ row }: { row: ToolRow }) {
  if (row.approval === 'approved') {
    return (
      <span className="tj-shield" title={`写确认模式 · 已人工确认${row.decidedBy ? `（${row.decidedBy}）` : ''}`}>
        <ShieldCheck size={12} />
      </span>
    )
  }
  if (row.approval === 'rejected') {
    return (
      <span className="tj-shield no" title="已拒绝执行">
        <ShieldX size={12} />
      </span>
    )
  }
  if (row.approval === 'pending') {
    return (
      <span className="tj-shield wait" title="等待人工确认">
        <ShieldQuestion size={12} />
      </span>
    )
  }
  return null
}

/** 38px 单行命令；输出 / 确认请求全部默认折叠，失败行浅红底。 */
function CommandRow({ row }: { row: ToolRow }) {
  const failed = row.status === 'failed' || (row.exitCode != null && row.exitCode !== 0)
  const cmdText = row.command?.trim() || previewArgs(row.arguments) || row.name
  const hasDetail = Boolean(row.output || row.confirmMessage)
  const title = `${formatMs(row.tsMs)}${row.hostName ? ` · ${row.hostName}` : ''}`
  const inner = (
    <>
      <span className="tj-cmd-ico"><ToolStatusIcon status={row.status} /></span>
      <span className="tj-cmd-tool">{getToolLabel(row.name)}</span>
      <span className="tj-cmd-args">{cmdText}</span>
      <span className="tj-cmd-meta">
        <ApprovalIcon row={row} />
        {row.exitCode != null && row.exitCode !== 0 && (
          <span className="tj-cmd-exit">exit {row.exitCode}</span>
        )}
        {row.durationMs != null && <span>{formatDuration(row.durationMs)}</span>}
        {row.output && <span>{row.output.length} 字符</span>}
        {hasDetail && (
          <ChevronRight size={13} className="tj-chev" />
        )}
      </span>
    </>
  )
  if (!hasDetail) {
    return (
      <div className={`tj-cmd is-static ${failed ? 'is-fail' : ''}`} title={title}>
        <div className="tj-cmd-row">{inner}</div>
      </div>
    )
  }
  return (
    <details className={`tj-cmd ${failed ? 'is-fail' : ''}`} title={title}>
      <summary className="tj-cmd-row">{inner}</summary>
      {row.confirmMessage && <div className="tj-cmd-confirm">确认请求：{row.confirmMessage}</div>}
      {row.output
        ? <pre className="tj-cmd-out">{row.output}</pre>
        : <div className="tj-cmd-out tj-cmd-out-empty">（无输出）</div>}
    </details>
  )
}

function TurnSection({ group, focusTurn }: { group: TurnGroup; focusTurn: number | null }) {
  const state = (group.end?.reason as TurnState | undefined) ?? 'running'
  const meta = TURN_STATE_META[state] ?? TURN_STATE_META.completed
  const running = state === 'running'

  const intentRow = group.items.find((i) => i.kind === 'user')
  const intent = intentRow && intentRow.kind === 'user' ? intentRow.text : ''
  const req = group.items.find((i) => i.kind === 'request')
  const modelId = req && req.kind === 'request' ? (req.model ?? undefined) : group.start?.modelId ?? undefined
  const mode = req && req.kind === 'request' ? (req.mode ?? undefined) : group.start?.mode ?? undefined
  const toolsCount = req && req.kind === 'request' ? req.toolsCount : undefined

  const commandRows = group.items.filter(
    (i): i is ToolRow => i.kind === 'tool' && !PREP_TOOLS.has(i.name),
  )
  const assistants = group.items.filter((i): i is AssistantRow => i.kind === 'assistant')

  const startMs = group.start?.tsMs
  const durationMs = group.start && group.end ? group.end.tsMs - group.start.tsMs : null
  const showResult = !running && state !== 'completed'
  const resultCls = state === 'error' ? 'error' : state === 'aborted' ? 'stop' : 'warn'
  const resultText = group.end?.errorMessage?.trim() || meta.label

  // 顺序展开内容：相邻的准备类工具合并；主机在每段命令前只显示一次。
  const nodes: ReactNode[] = []
  let prepBuf: ToolRow[] = []
  let lastHost: string | null | undefined = undefined
  const flushPrep = (key: string) => {
    if (prepBuf.length === 0) return
    nodes.push(
      <div className="tj-prep" key={key}>
        <Check size={12} className="tj-ico-ok" />
        准备：{prepBuf.map((t) => getToolLabel(t.name)).join(' · ')}
      </div>,
    )
    prepBuf = []
  }
  group.items.forEach((row) => {
    if (row.kind === 'request') return
    if (row.kind === 'tool' && PREP_TOOLS.has(row.name)) {
      prepBuf.push(row)
      return
    }
    flushPrep(`prep-${row.seq}`)
    switch (row.kind) {
      case 'user':
        nodes.push(
          <div className="tj-msg" key={`u-${row.seq}`}>
            <span className="tj-tag tj-tag-user">用户{row.source === 'inject' ? ' · 插话' : ''}</span>
            <p className="tj-prose">{row.text}</p>
          </div>,
        )
        lastHost = undefined
        break
      case 'assistant': {
        if (row.reasoning) {
          nodes.push(
            <details className="tj-think" key={`th-${row.seq}`}>
              <summary>
                <ChevronRight size={11} className="tj-chev" />
                思考 · {row.reasoning.length} 字
              </summary>
              <p className="tj-prose dim">{row.reasoning}</p>
            </details>,
          )
        }
        if (row.text) {
          nodes.push(
            <div className="tj-msg" key={`a-${row.seq}`}>
              <span className="tj-tag tj-tag-assistant">回复</span>
              <p className="tj-prose">{row.text}</p>
            </div>,
          )
        }
        lastHost = undefined
        break
      }
      case 'tool': {
        const host = row.hostName ?? null
        if (host && host !== lastHost) {
          nodes.push(
            <div className="tj-cmd-host" key={`host-${row.seq}`}>
              <Server size={12} />
              {host}
            </div>,
          )
        }
        lastHost = host
        nodes.push(<CommandRow key={`cmd-${row.seq}`} row={row} />)
        break
      }
      case 'interaction':
        nodes.push(
          <div className="tj-msg" key={`i-${row.interactionId}`}>
            <span className="tj-tag tj-tag-warn">
              <HelpCircle size={11} />
              主动询问{row.resolved ? ' · 已回答' : ' · 等待中'}
            </span>
            <div className="tj-msg-body">
              <p className="tj-prose">{row.question}</p>
              {row.options && row.options.length > 0 && (
                <div className="tj-opts">
                  {row.options.map((o) => <span key={o} className="tj-opt">{o}</span>)}
                </div>
              )}
              {row.resolved && (
                <div className="tj-answer">
                  {row.skipped
                    ? (row.interrupted ? '已跳过（会话冷中断）' : '已跳过')
                    : row.answer || '（空回答）'}
                </div>
              )}
            </div>
          </div>,
        )
        lastHost = undefined
        break
      case 'compaction':
        nodes.push(
          <details className="tj-note" key={`c-${row.seq}`}>
            <summary>
              <Archive size={11} />
              上下文压缩（#{row.replacedFromSeq} → #{row.replacedToSeq}）
            </summary>
            <p className="tj-prose dim">{row.summary}</p>
          </details>,
        )
        lastHost = undefined
        break
      case 'error':
        nodes.push(
          <div className="tj-msg" key={`e-${row.seq}`}>
            <span className="tj-tag tj-tag-error">
              <AlertTriangle size={11} />
              错误{row.phase ? ` · ${row.phase}` : ''}
            </span>
            <p className="tj-prose danger">{row.message}</p>
          </div>,
        )
        lastHost = undefined
        break
      default:
        break
    }
  })
  flushPrep('prep-end')

  const metricRows = assistants.filter((r) => r.ttftMs != null || r.usage)

  return (
    <details
      id={`tj-turn-${group.turnNo}`}
      className={`tj-turn ${state === 'error' ? 'is-error' : ''}`}
      open={focusTurn === group.turnNo}
    >
      <summary>
        <ChevronRight size={14} className="tj-chev" />
        <span className={`obs-state-dot tone-${meta.tone} tj-turn-dot ${running ? 'live' : ''}`} />
        <span className="tj-turn-no">第 {group.turnNo} 轮</span>
        <span className="tj-turn-intent" title={intent}>{intent || '（本轮无用户消息）'}</span>
        <span className="tj-turn-meta">
          <span className="tj-turn-cmds" title="工具调用次数（不含内部准备）">
            <TerminalIcon size={12} />
            {commandRows.length} 命令
          </span>
          {showResult && (
            <span className={`tj-turn-result ${resultCls}`} title={resultText}>{resultText}</span>
          )}
          <span>{running ? '运行中' : formatDuration(durationMs)}</span>
          <span title={formatMs(startMs)}>{formatMs(startMs, false)}</span>
        </span>
      </summary>
      <div className="tj-turn-body">
        <div className="tj-turn-ctx">
          {modelId && <code>{modelId}</code>}
          {mode && <span className="tj-mode">{MODE_LABEL[mode] ?? mode}</span>}
          {toolsCount != null && <span>{toolsCount} 个可用工具</span>}
        </div>
        {nodes}
        {metricRows.length > 0 && (
          <details className="tj-metrics">
            <summary>
              <ChevronRight size={11} className="tj-chev" />
              性能指标（调试）
            </summary>
            <table>
              <tbody>
                {metricRows.map((r) => {
                  const genMs = r.firstChunkMs && r.messageTsMs
                    ? Math.max(0, r.messageTsMs - r.firstChunkMs)
                    : null
                  return (
                    <tr key={r.seq}>
                      <td>步骤 {r.stepNo}</td>
                      {r.ttftMs != null && <td>TTFT {formatDuration(r.ttftMs)}</td>}
                      {genMs != null && <td>生成 {formatDuration(genMs)}</td>}
                      {r.usage && (
                        <td>
                          Tokens {tokens(r.usage.input)} → {tokens(r.usage.output)}
                          {r.usage.total ? ` · ${tokens(r.usage.total)}` : ''}
                        </td>
                      )}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </details>
        )}
      </div>
    </details>
  )
}

function TimelineRenderer({ rows, focusTurn }: { rows: TrajectoryRow[]; focusTurn: number | null }) {
  const groups = useMemo(() => groupTurns(rows), [rows])
  return (
    <div className="tj-turns">
      {groups.map((g) => (
        <TurnSection key={g.turnNo} group={g} focusTurn={focusTurn} />
        ))}
    </div>
  )
}

function TrajectoryDrawer({
  session,
  onClose,
}: {
  session: SessionRow
  onClose: () => void
}) {
  const navigate = useNavigate()
  const [data, setData] = useState<TrajectoryResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const bodyRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true); setErr(null); setData(null)
    obsApi.trajectory(session.sessionId)
      .then((r) => { if (!cancelled) setData(r) })
      .catch((e) => { if (!cancelled) setErr(e?.message ? String(e.message) : '轨迹加载失败') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [session.sessionId])

  return (
    <Drawer
      title={
        <div className="tj-drawer-title">
          <StateDot state={session.state} />
          <span className="tj-drawer-name">{session.sessionTitle?.trim() || shortSession(session.sessionId)}</span>
          <span className="logs-detail-id">{session.turnCount} 轮</span>
          {session.hostName && <span className="logs-host">{session.hostName}</span>}
        </div>
      }
      placement="right"
      width={760}
      open
      onClose={onClose}
      destroyOnClose
    >
      <div className="tj-drawer-body" ref={bodyRef}>
        <div className="tj-drawer-actions">
          <button
            type="button"
            className="dsh-btn dsh-btn-sm dsh-btn-outline"
            onClick={() => navigate(`/chat/${session.sessionId}`)}
          >
            <MessageSquare size={13} /> 打开对话
          </button>
        </div>

        {loading && <div className="logs-empty"><div className="logs-empty-icon"><ChartLine /></div><div>轨迹加载中…</div></div>}
        {err && <div className="logs-empty"><div className="logs-empty-icon"><AlertTriangle /></div><div>{err}</div></div>}
        {data && data.rows.length === 0 && (
          <div className="logs-empty">
            <div className="logs-empty-icon"><ChartLine /></div>
            <div>该会话暂无事件轨迹</div>
            <div className="logs-empty-hint">该会话还没有对话，发起对话后这里会展示完整事件时间线</div>
          </div>
        )}
        {data && data.rows.length > 0 && (
          <TimelineRenderer rows={data.rows} focusTurn={null} />
        )}
      </div>
    </Drawer>
  )
}

// ============================================================
// 主页面：会话账本（一个对话一行，按服务器分组）
// ============================================================

interface HostGroup {
  key: string
  hostName: string
  sessions: SessionRow[]
}

/** 保持后端返回顺序（最近活动优先），按主机折叠成分组。 */
function groupByHost(rows: SessionRow[]): HostGroup[] {
  const groups: HostGroup[] = []
  const index = new Map<string, HostGroup>()
  for (const s of rows) {
    const key = s.hostId != null ? `h${s.hostId}` : 'none'
    let g = index.get(key)
    if (!g) {
      g = { key, hostName: s.hostName || '', sessions: [] }
      index.set(key, g)
      groups.push(g)
    }
    g.sessions.push(s)
  }
  return groups
}

export default function TracePage() {
  const [sessions, setSessions] = useState<SessionRow[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [state, setState] = useState('')
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [selected, setSelected] = useState<SessionRow | null>(null)
  const [now, setNow] = useState(Date.now())
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async (p: number) => {
    setLoading(true)
    try {
      const params: Record<string, any> = { limit: PAGE_SIZE, offset: (p - 1) * PAGE_SIZE }
      if (search.trim()) params.search = search.trim()
      if (state) params.state = state
      const res = await obsApi.sessions(params)
      setSessions(res.sessions || [])
      setTotal(res.total)
    } catch (e) {
      console.error('Failed to load sessions:', e)
    } finally {
      setLoading(false)
    }
  }, [search, state])

  useEffect(() => { void load(page) }, [load, page])

  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(() => { void load(page); setNow(Date.now()) }, AUTO_REFRESH_MS)
    return () => clearInterval(timer)
  }, [autoRefresh, load, page])

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const groups = useMemo(() => groupByHost(sessions), [sessions])

  const summary = useMemo(() => {
    const running = sessions.filter((s) => s.state === 'running').length
    const errors = sessions.filter((s) => s.errorTurns > 0).length
    return { running, errors }
  }, [sessions])

  return (
    <div className="logs-page logs-page-v2 obs-page">
      <div className="logs-toolbar-v2 tj-toolbar">
        <div className="dsh-input-wrap logs-search">
          <span className="dsh-input-icon"><Search /></span>
          <input
            className="dsh-input"
            placeholder="搜索会话标题 / 会话 ID / 模型"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              if (searchTimer.current) clearTimeout(searchTimer.current)
              searchTimer.current = setTimeout(() => setPage(1), 400)
            }}
          />
        </div>
        <select className="logs-select" value={state} onChange={(e) => { setState(e.target.value); setPage(1) }}>
          <option value="">全部状态</option>
          {Object.entries(TURN_STATE_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </select>
        <button
          type="button"
          className={`dsh-btn dsh-btn-sm ${autoRefresh ? 'dsh-btn-primary' : 'dsh-btn-outline'} obs-auto-btn`}
          onClick={() => setAutoRefresh((v) => !v)}
        >
          {autoRefresh ? <><Radio className="obs-live-dot" /> 实时</> : <><Pause /> 已暂停</>}
        </button>
        <button type="button" className="dsh-icon-btn logs-refresh-btn" title="刷新" onClick={() => void load(page)} disabled={loading}>
          <RotateCw className={loading ? 'dsh-spin' : undefined} />
        </button>
      </div>

      <div className="logs-body-v2">
        <div className="logs-list-head">
          <span>共 {total.toLocaleString()} 个对话 · 按服务器分组 · 点击行查看完整轨迹（请求 / 工具 / token / 中断闭合）</span>
          {(summary.running > 0 || summary.errors > 0) && (
            <span className="tj-head-hint">
              {summary.running > 0 && <><StateDot state="running" size={7} /> {summary.running} 运行中</>}
              {summary.errors > 0 && <><StateDot state="error" size={7} /> {summary.errors} 含异常轮次</>}
            </span>
          )}
        </div>

        <div className="tj-ledger">
          <div className="tj-ledger-head">
            <span className="col-state" />
            <span className="col-session">对话</span>
            <span className="col-model">模型 / 模式</span>
            <span className="col-steps">步 / 工具</span>
            <span className="col-tokens">Tokens 入→出</span>
            <span className="col-duration">耗时</span>
            <span className="col-time">最近活动</span>
            <span className="col-open" />
          </div>

          <div className="logs-list tj-ledger-body">
            {loading && sessions.length === 0 && <div className="logs-empty">加载中…</div>}
            {!loading && sessions.length === 0 && (
              <div className="logs-empty">
                <div className="logs-empty-icon"><ChartLine /></div>
                <div>暂无 Agent 运行轨迹</div>
                <div className="logs-empty-hint">开始对话后，每个会话都会记录完整轨迹</div>
              </div>
            )}
            {groups.map((g) => (
              <div className="tj-host-block" key={g.key}>
                <div className="tj-host-head">
                  <Server size={13} />
                  <span className="tj-host-name">{g.hostName || '未绑定主机'}</span>
                  <span className="tj-host-count">{g.sessions.length} 个对话</span>
                </div>
                {g.sessions.map((s) => {
                  const meta = TURN_STATE_META[s.state] ?? TURN_STATE_META.completed
                  const live = s.state === 'running'
                  // 运行中：已完成轮次耗时 + 当前轮已运行（从最近一轮开始计）。
                  const duration = live ? s.durationMs + Math.max(0, now - s.lastMs) : s.durationMs
                  return (
                    <div
                      key={s.sessionId}
                      className={`tj-ledger-row ${live ? 'is-live' : ''} ${selected?.sessionId === s.sessionId ? 'selected' : ''}`}
                      onClick={() => setSelected(s)}
                    >
                      <span className="col-state"><StateDot state={s.state} /></span>
                      <span className="col-session">
                        <span className="tj-session-name">{s.sessionTitle?.trim() || shortSession(s.sessionId)}</span>
                        <span className="tj-turn-no">{s.turnCount} 轮</span>
                        {(s.interactions > 0 || s.errorTurns > 0) && (
                          <span className="tj-flags">
                            {s.interactions > 0 && <span className="tj-flag warn">{s.interactions} 交互</span>}
                            {s.errorTurns > 0 && <span className="tj-flag danger">{s.errorTurns} 异常</span>}
                          </span>
                        )}
                      </span>
                      <span className="col-model">
                        <code className="tj-model-cell">{s.modelId || '-'}</code>
                        {s.mode && <span className="tj-mode">{MODE_LABEL[s.mode] ?? s.mode}</span>}
                      </span>
                      <span className="col-steps mono">
                        {s.steps} <span className="tj-sep">/</span> {s.toolCalls}
                      </span>
                      <span className="col-tokens mono">
                        {tokens(s.tokensIn)} → {tokens(s.tokensOut)}
                      </span>
                      <span className="col-duration mono">{formatDuration(duration)}</span>
                      <span className="col-time" title={formatMs(s.lastMs)}>{formatRelative(s.lastMs)}</span>
                      <span className="col-open">
                        <ChevronRight size={14} className={`tone-${meta.tone}`} />
                      </span>
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        </div>

        {totalPages > 1 && (
          <div className="logs-pagination">
            <button className="dsh-btn dsh-btn-sm dsh-btn-outline" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
              上一页
            </button>
            <span className="logs-page-info">{page} / {totalPages}</span>
            <button className="dsh-btn dsh-btn-sm dsh-btn-outline" disabled={page >= totalPages} onClick={() => setPage((p) => p + 1)}>
              下一页
            </button>
          </div>
        )}
      </div>

      {selected && <TrajectoryDrawer session={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
