import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  SquareTerminal,
  Folder,
  FileText,
  FilePen,
  Upload,
  Trash2,
  FolderPlus,
  Search,
  Wrench,
  RotateCw,
  X,
  ListFilter,
  FileSearch,
  FileDiff,
  ArrowLeftRight,
  ListChecks,
  MessageSquare,
  Radio,
  Pause,
} from 'lucide-react'
import { Drawer, Popover } from 'antd'
import obsApi, {
  type ActivityRow,
  type ActivityStats,
  type ActivityStatus,
  type ActivityApproval,
} from '@/api/observabilityApi'
import { getToolLabel, getToolColor } from '@/api/auditApi'
import hostApi from '@/api/hostApi'
import '@/styles/observability.css'

const PAGE_SIZE = 50
/** 操作流自动刷新间隔（ms）。运行中的任务可看到状态实时闭合。 */
const AUTO_REFRESH_MS = 10_000

const TOOL_ICON: Record<string, ReactNode> = {
  run_ssh: <SquareTerminal />,
  sftp_list: <Folder />,
  sftp_read: <FileText />,
  sftp_write: <FilePen />,
  sftp_upload: <Upload />,
  sftp_remove: <Trash2 />,
  sftp_mkdir: <FolderPlus />,
  sftp_edit: <FileDiff />,
  sftp_rename: <ArrowLeftRight />,
  remote_grep: <Search />,
  web_search: <Search />,
  web_fetch: <FileText />,
  todo_write: <ListChecks />,
  tool_search: <Search />,
}

function toolIcon(toolName: string): ReactNode {
  return TOOL_ICON[toolName] ?? <Wrench />
}

function pad(n: number): string {
  return n.toString().padStart(2, '0')
}

function formatMs(ms?: number | null): string {
  if (!ms) return '-'
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return '-'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function formatClock(ms?: number | null): string {
  if (!ms) return '--:--:--'
  const d = new Date(ms)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 相对时间（x分钟前 / x小时前 / 日期）。 */
function formatRelative(ms?: number | null): string {
  if (!ms) return '-'
  const diff = Date.now() - ms
  if (diff < 0) return '刚刚'
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const d = new Date(ms)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function formatDuration(ms?: number | null): string {
  if (ms === undefined || ms === null || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m${Math.floor((ms % 60000) / 1000)}s`
}

type Tone = 'ok' | 'warn' | 'danger' | 'neutral'

const STATUS_META: Record<ActivityStatus, { label: string; tone: Tone }> = {
  running: { label: '运行中', tone: 'neutral' },
  success: { label: '成功', tone: 'ok' },
  failed: { label: '失败', tone: 'danger' },
  interrupted: { label: '中断', tone: 'warn' },
}

const APPROVAL_META: Record<ActivityApproval, { label: string; tone: Tone }> = {
  auto: { label: '自动执行', tone: 'neutral' },
  approved: { label: '已确认', tone: 'ok' },
  rejected: { label: '已拒绝', tone: 'danger' },
  pending: { label: '等待确认', tone: 'warn' },
}

/** 权限模式原文 → 中文标签（旧实现直接渲染 write_confirm 原文，易被误读成主机名）。 */
const MODE_LABEL: Record<string, string> = {
  readonly: '只读',
  write_confirm: '写确认',
  all_confirm: '全确认',
}
function modeLabel(mode?: string | null): string {
  if (!mode) return ''
  return MODE_LABEL[mode] ?? mode
}

/** date input（yyyy-MM-dd，本地时区）→ 当天起止 epoch ms */
function dayStartMs(date: string): number {
  const d = new Date(`${date}T00:00:00`)
  return d.getTime()
}
function dayEndMs(date: string): number {
  const d = new Date(`${date}T23:59:59.999`)
  return d.getTime()
}

function shortSession(id: string): string {
  const a = id.split('-')
  return a.length > 1 ? `${a[0]}…${a[a.length - 1].slice(0, 6)}` : id.slice(0, 10)
}

export default function LogsPage() {
  const navigate = useNavigate()
  const [rows, setRows] = useState<ActivityRow[]>([])
  const [total, setTotal] = useState(0)
  const [stats, setStats] = useState<ActivityStats | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<ActivityRow | null>(null)

  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [toolName, setToolName] = useState('')
  const [approval, setApproval] = useState('')
  const [hostId, setHostId] = useState<number | undefined>(undefined)
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [advOpen, setAdvOpen] = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(true)

  const [toolOptions, setToolOptions] = useState<string[]>([])
  const [hosts, setHosts] = useState<{ id: number; name: string }[]>([])
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    obsApi.activity({ limit: 1 }).then((r) => setToolOptions(r.tools ?? [])).catch(() => {})
    hostApi.list().then((hs) => setHosts(hs.map((h) => ({ id: h.id ?? 0, name: h.name })))).catch(() => {})
  }, [])

  const load = useCallback(async (p: number) => {
    setLoading(true)
    try {
      const params: Record<string, any> = {
        limit: PAGE_SIZE,
        offset: (p - 1) * PAGE_SIZE,
      }
      if (search.trim()) params.search = search.trim()
      if (toolName) params.toolName = toolName
      if (status) params.status = status
      if (approval) params.approvalStatus = approval
      if (hostId !== undefined) params.hostId = hostId
      if (startDate) params.startMs = dayStartMs(startDate)
      if (endDate) params.endMs = dayEndMs(endDate)

      const result = await obsApi.activity(params)
      setRows(result.rows)
      setTotal(result.total)
      setStats(result.stats)
      setToolOptions(result.tools ?? [])
      // 详情抽屉开着时，用最新数据同步同一 callId（状态可能刚刚闭合）
      setSelected((prev) => prev ? (result.rows.find((l) => l.callId === prev.callId) ?? prev) : null)
    } catch (e) {
      console.error('Failed to load activity:', e)
    } finally {
      setLoading(false)
    }
  }, [search, toolName, status, approval, hostId, startDate, endDate])

  useEffect(() => {
    void load(page)
  }, [load, page])

  // 自动刷新：轮询当前视图（停留在第一页时体验最接近实时操作流）
  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(() => { void load(page) }, AUTO_REFRESH_MS)
    return () => clearInterval(timer)
  }, [autoRefresh, load, page])

  const onSearchChange = (v: string) => {
    setSearch(v)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    searchTimer.current = setTimeout(() => setPage(1), 400)
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const successRate = useMemo(
    () => (stats && stats.total > 0
      ? ((stats.success / stats.total) * 100).toFixed(1)
      : '0.0'),
    [stats],
  )

  const advCount = [toolName, approval, hostId !== undefined ? 'x' : '', startDate, endDate].filter(Boolean).length

  const clearAdv = () => {
    setToolName(''); setApproval(''); setHostId(undefined)
    setStartDate(''); setEndDate(''); setPage(1)
  }

  const advPanel = (
    <div className="logs-adv-panel">
      <label className="logs-adv-field">
        <span>工具</span>
        <select className="dsh-input" value={toolName} onChange={(e) => { setToolName(e.target.value); setPage(1) }}>
          <option value="">全部工具</option>
          {toolOptions.map((t) => <option key={t} value={t}>{getToolLabel(t)}</option>)}
        </select>
      </label>
      <label className="logs-adv-field">
        <span>审批</span>
        <select className="dsh-input" value={approval} onChange={(e) => { setApproval(e.target.value); setPage(1) }}>
          <option value="">全部审批</option>
          {Object.entries(APPROVAL_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </select>
      </label>
      <label className="logs-adv-field">
        <span>主机</span>
        <select className="dsh-input" value={hostId ?? ''} onChange={(e) => { setHostId(e.target.value ? Number(e.target.value) : undefined); setPage(1) }}>
          <option value="">全部主机</option>
          {hosts.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
        </select>
      </label>
      <label className="logs-adv-field">
        <span>开始日期</span>
        <input type="date" className="dsh-input" value={startDate} onChange={(e) => { setStartDate(e.target.value); setPage(1) }} />
      </label>
      <label className="logs-adv-field">
        <span>结束日期</span>
        <input type="date" className="dsh-input" value={endDate} onChange={(e) => { setEndDate(e.target.value); setPage(1) }} />
      </label>
      {advCount > 0 && (
        <button type="button" className="dsh-btn dsh-btn-sm dsh-btn-ghost" onClick={clearAdv}>
          清空筛选
        </button>
      )}
    </div>
  )

  const openChat = (row: ActivityRow) => navigate(`/chat/${row.sessionId}`)

  return (
    <div className="logs-page logs-page-v2 obs-page">
      {/* 指标带 */}
      <div className="logs-metrics">
        <span className="logs-metric"><b>{stats?.total.toLocaleString() ?? '-'}</b> 总操作</span>
        <span className="logs-metric ok"><b>{successRate}%</b> 成功率</span>
        <span className="logs-metric danger"><b>{((stats?.failed ?? 0) + (stats?.interrupted ?? 0)).toLocaleString()}</b> 失败/中断</span>
        <span className="logs-metric"><b className={stats && stats.running > 0 ? 'obs-live-num' : ''}>{stats?.running.toLocaleString() ?? '-'}</b> 运行中</span>
        <span className="logs-metric"><b>{stats?.today.toLocaleString() ?? '-'}</b> 今日</span>
        <button
          type="button"
          className={`dsh-btn dsh-btn-sm ${autoRefresh ? 'dsh-btn-primary' : 'dsh-btn-outline'} obs-auto-btn`}
          onClick={() => setAutoRefresh((v) => !v)}
          title={autoRefresh ? '暂停自动刷新' : '开启自动刷新（10 秒）'}
        >
          {autoRefresh ? <><Radio className="obs-live-dot" /> 实时</> : <><Pause /> 已暂停</>}
        </button>
      </div>

      {/* 筛选工具栏 */}
      <div className="logs-toolbar-v2">
        <div className="dsh-input-wrap logs-search">
          <span className="dsh-input-icon"><Search /></span>
          <input
            className="dsh-input"
            placeholder="搜索命令 / 参数 / 输出 / 会话标题"
            value={search}
            onChange={(e) => onSearchChange(e.target.value)}
          />
        </div>
        <select className="logs-select" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1) }}>
          <option value="">全部状态</option>
          {Object.entries(STATUS_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </select>
        <Popover
          trigger="click"
          open={advOpen}
          onOpenChange={setAdvOpen}
          placement="bottomLeft"
          title="高级筛选"
          content={advPanel}
        >
          <button type="button" className={`dsh-btn dsh-btn-sm ${advCount > 0 ? 'dsh-btn-primary' : 'dsh-btn-outline'} logs-adv-btn`}>
            <ListFilter /> 高级筛选{advCount > 0 ? ` · ${advCount}` : ''}
          </button>
        </Popover>
        <button type="button" className="dsh-icon-btn logs-refresh-btn" title="刷新" onClick={() => void load(page)} disabled={loading}>
          <RotateCw className={loading ? 'dsh-spin' : undefined} />
        </button>
      </div>

      {/* 生效中的高级筛选 chip */}
      {advCount > 0 && (
        <div className="logs-chips">
          {toolName && <span className="logs-chip">{getToolLabel(toolName)} <X onClick={() => { setToolName(''); setPage(1) }} /></span>}
          {approval && <span className="logs-chip">{APPROVAL_META[approval as ActivityApproval]?.label ?? approval} <X onClick={() => { setApproval(''); setPage(1) }} /></span>}
          {hostId !== undefined && <span className="logs-chip">{hosts.find((h) => h.id === hostId)?.name ?? `主机#${hostId}`} <X onClick={() => { setHostId(undefined); setPage(1) }} /></span>}
          {startDate && <span className="logs-chip">起 {startDate} <X onClick={() => { setStartDate(''); setPage(1) }} /></span>}
          {endDate && <span className="logs-chip">止 {endDate} <X onClick={() => { setEndDate(''); setPage(1) }} /></span>}
        </div>
      )}

      {/* 列表 */}
      <div className="logs-body-v2">
        <div className="logs-list-head">
          <span>共 {total.toLocaleString()} 条操作 · 事件流水（最近 6000 个工具事件窗口）</span>
          {loading && <span className="logs-loading-hint">加载中…</span>}
        </div>
        <div className="logs-list">
          {loading && rows.length === 0 && <div className="logs-empty">加载中…</div>}
          {!loading && rows.length === 0 && (
            <div className="logs-empty">
              <div className="logs-empty-icon"><FileSearch /></div>
              <div>暂无操作记录</div>
              <div className="logs-empty-hint">AI 执行的服务器操作会实时留痕在这里</div>
            </div>
          )}
          {rows.map((log) => {
            const st = STATUS_META[log.status] ?? { label: log.status, tone: 'neutral' as Tone }
            const ap = APPROVAL_META[log.approval] ?? { label: log.approval, tone: 'neutral' as Tone }
            const live = log.status === 'running'
            return (
              <div
                key={log.callId}
                className={`logs-row obs-row ${selected?.callId === log.callId ? 'selected' : ''} ${live ? 'is-live' : ''}`}
                onClick={() => setSelected(log)}
              >
                <span className="logs-row-icon" style={{ color: getToolColor(log.toolName) }}>
                  {toolIcon(log.toolName)}
                </span>
                <div className="logs-row-main">
                  <div className="logs-row-top">
                    <span className="logs-time">{formatClock(log.tsMs)}</span>
                    <span className="logs-tool">{getToolLabel(log.toolName)}</span>
                    {log.hostName
                      ? <span className="logs-host">{log.hostName}</span>
                      : log.hostId != null && <span className="logs-host">主机#{log.hostId}</span>}
                    {log.mode && <span className="logs-mode">{modeLabel(log.mode)}</span>}
                    <span
                      className="obs-session-chip"
                      title={`打开对话 · 第 ${log.turnNo} 轮 #${log.seq}`}
                      onClick={(e) => { e.stopPropagation(); openChat(log) }}
                    >
                      <MessageSquare size={11} />
                      {log.sessionTitle?.trim() || shortSession(log.sessionId)} · T{log.turnNo}
                    </span>
                    <span className="obs-rel-time">{formatRelative(log.tsMs)}</span>
                  </div>
                  <div className="logs-command" title={log.command ?? ''}>
                    <code>{log.command || log.toolName}</code>
                  </div>
                  <div className="logs-row-bottom">
                    {live && <span className="obs-live-pulse" />}
                    <span className={`logs-badge tone-${st.tone}`}>{st.label}</span>
                    <span className={`logs-badge tone-${ap.tone}`}>{ap.label}</span>
                    <span className="logs-duration">
                      {live ? formatDuration(log.elapsedMs) : formatDuration(log.durationMs)}
                    </span>
                    {log.exitCode !== undefined && log.exitCode !== null && (
                      <span className={`logs-exit tone-${log.exitCode === 0 ? 'ok' : 'danger'}`}>
                        exit: {log.exitCode}
                      </span>
                    )}
                    {log.interrupted && <span className="logs-interrupted-tag">冷恢复闭合</span>}
                  </div>
                </div>
              </div>
            )
          })}
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

      <Drawer
        title={selected ? (
          <div className="logs-detail-title">
            <span className="logs-detail-icon" style={{ color: getToolColor(selected.toolName) }}>
              {toolIcon(selected.toolName)}
            </span>
            <span>{getToolLabel(selected.toolName)}</span>
            <span className="logs-detail-id">T{selected.turnNo} · #{selected.seq}</span>
          </div>
        ) : undefined}
        placement="right"
        width={560}
        open={selected !== null}
        onClose={() => setSelected(null)}
        destroyOnClose
      >
        {selected && (
          <div className="logs-detail-body">
            <div className="logs-section">
              <div className="logs-section-title">基本信息</div>
              <div className="logs-grid">
                <div className="logs-item"><span className="logs-k">时间</span><span className="logs-v mono">{formatMs(selected.tsMs)}</span></div>
                <div className="logs-item"><span className="logs-k">主机</span><span className="logs-v">{selected.hostName || (selected.hostId != null ? `主机#${selected.hostId}` : '-')}</span></div>
                <div className="logs-item"><span className="logs-k">执行模式</span><span className="logs-v">{modeLabel(selected.mode) || '-'}</span></div>
                <div className="logs-item">
                  <span className="logs-k">状态</span>
                  <span className={`logs-v tone-${STATUS_META[selected.status]?.tone ?? 'neutral'}`}>
                    {STATUS_META[selected.status]?.label ?? selected.status}
                  </span>
                </div>
                <div className="logs-item">
                  <span className="logs-k">审批</span>
                  <span className={`logs-v tone-${APPROVAL_META[selected.approval]?.tone ?? 'neutral'}`}>
                    {APPROVAL_META[selected.approval]?.label ?? selected.approval}
                  </span>
                </div>
                <div className="logs-item">
                  <span className="logs-k">耗时</span>
                  <span className="logs-v">
                    {selected.status === 'running' ? formatDuration(selected.elapsedMs) : formatDuration(selected.durationMs)}
                  </span>
                </div>
                <div className="logs-item">
                  <span className="logs-k">退出码</span>
                  <span className={`logs-v ${selected.exitCode === 0 ? 'tone-ok' : selected.exitCode == null ? '' : 'tone-danger'}`}>{selected.exitCode ?? '-'}</span>
                </div>
                <div className="logs-item">
                  <span className="logs-k">轮次</span>
                  <span className="logs-v mono">turn {selected.turnNo} · step {selected.stepNo}</span>
                </div>
              </div>
              <button
                type="button"
                className="dsh-btn dsh-btn-sm dsh-btn-outline obs-open-chat-btn"
                onClick={() => openChat(selected)}
              >
                <MessageSquare size={13} /> 打开所属对话
                <span className="obs-open-chat-title">{selected.sessionTitle?.trim() || shortSession(selected.sessionId)}</span>
              </button>
            </div>
            <div className="logs-section">
              <div className="logs-section-title">命令 / 操作</div>
              <pre className="logs-code">{selected.command || selected.toolName}</pre>
            </div>
            {Boolean(selected.arguments) && Object.keys(selected.arguments as object).length > 0 && (
              <div className="logs-section">
                <div className="logs-section-title">参数</div>
                <pre className="logs-code">{JSON.stringify(selected.arguments, null, 2)}</pre>
              </div>
            )}
            <div className="logs-section">
              <div className="logs-section-title">输出</div>
              <pre className="logs-code output">{selected.output || (selected.status === 'running' ? '（执行中，尚无输出）' : '(无输出)')}</pre>
            </div>
          </div>
        )}
      </Drawer>
    </div>
  )
}
