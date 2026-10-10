import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { message as antdMessage, Tooltip } from 'antd'
import {
  SquareTerminal,
  Folder,
  Power,
  FileText,
  Home,
  ArrowUp,
  RotateCw,
  Upload,
  FolderUp,
  FolderPlus,
  FileArchive,
  ShieldCheck,
  ScrollText,
  HardDrive,
  Rocket,
  FolderOpen,
  ChevronDown,
  X,
  type LucideIcon,
} from 'lucide-react'
import SshTerminalPanel, { type SshTerminalPanelRef } from '@/components/terminal/SshTerminalPanel'
import ServerFileTree, { type ServerFileTreeRef } from '@/components/files/ServerFileTree'
import UploadManager from '@/components/files/UploadManager'
import PanelResizer from '@/components/layout/PanelResizer'
import FxNavIcon from '@/components/fx/FxNavIcon'
import ModelSelect, { type ModelSelection, type ModelSelectGroup } from '@/components/chat/ModelSelect'
import PermissionModeSelect from '@/components/chat/PermissionModeSelect'
import InputBar, { type ComposerSubmitPayload, type FileTag } from '@/components/chat/InputBar'
import { UserMessage, AssistantMessage, type ChatMessage, type AssistantBlock } from '@/components/chat/MessageItem'
import type { ToolCallRecord } from '@/components/chat/ToolCard'
import { HostConnectBar } from '@/components/chat/HostConnectBar'
import {
  ComposerApprovalCard, ComposerQuestionCard,
  type ConfirmationCardData, type AskCardData,
} from '@/components/chat/InteractionCards'
import { errorMessage, listConfiguredProviders, type ProviderProfile } from '@/api/modelApi'
import {
  startTurn, cancelTurn,
  type ExecutionMode,
} from '@/api/agentV2Api'
import {
  ET,
  type SessionEvent,
} from '@/api/sessionEventsApi'
import sessionsApi, { type SessionMessage } from '@/api/sessionsApi'
import { useHostStore, selectCurrentHost, selectCurrentState, selectSessionId } from '@/store/useHostStore'
import { useSessionsStore } from '@/store/useSessionsStore'
import { useUploadStore } from '@/store/useUploadStore'
import {
  useLiveTurn,
  attachSessionRuntime,
  detachSessionRuntime,
  addRuntimeEventListener,
} from '@/store/useSessionRuntime'
import type { LiveTurnView } from '@/lib/liveTurnProjector'
import { getStoredModelSelection, setStoredModelSelection } from '@/lib/modelSelection'
import { getLastSessionId, setLastSessionId } from '@/lib/prefs'
import {
  hostKeyOf, isMutationTool, parseSshResult, resultOutputText, clampForTerminal,
} from '@/lib/remoteTools'
import WorkspacePicker from '@/components/deploy/WorkspacePicker'
import { DeployDock, standingDeployFromMessages, deployIsFinished } from '@/components/deploy/DeployDock'
import { DeployRocketIcon } from '@/components/deploy/DeployRocketIcon'
import { TodoDock, standingTodoFromMessages } from '@/components/chat/TodoDock'
import {
  getBoundWorkspace,
  unbindWorkspace,
  type Workspace,
} from '@/api/deployApi'

/**
 * 对话页 —— 1:1 复刻 dsh ConversationRoot 的组合：
 *   - 中部：消息流（MessageItem 气泡 + 思考折叠 + markdown + 统计行），空态显示 hero
 *   - 底部：InputBar（ui-conversation skeleton），模型座挂在右下工具槽
 *
 * 后端接线（模型目录）：
 *   - 组件挂载时调 GET /api/models/providers?configuredOnly=true（listConfiguredProviders）
 *     拉取「已配置 API Key」的厂商列表；后端不可达时目录留空并弹 antd.warning 提示。
 *   - 默认选中项（current）必须在 groups 的 options 集合中，不匹配时按 §经验 1807893
 *     自动回退到 groups[0].models[0] 并 antd.warn 提示原因。
 *   - 发送走真实 SSE（POST /api/agent/chat/stream），消费 start/thinking/text/tool_call/
 *     tool_result/error/done；Stop 通过 AbortController 中止。
 */

/** 支持思考模式的模型附带的推理档位（Aye 侧 mock）。 */
const REASONING = {
  defaultEffort: 'high',
  efforts: [
    { id: 'low', name: '低', description: '最快，适合简单任务' },
    { id: 'medium', name: '中', description: '均衡' },
    { id: 'high', name: '高', description: '更深入，适合复杂推理' },
  ],
}

const nextId = (prefix: string): string => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`

/** 乐观用户行：__optSid 为发送时所属会话（新会话首条消息在 startTurn 返回前为 null）。 */
type OptimisticMessage = ChatMessage & { __optSid: string | null }

/**
 * 当前右侧面板所连主机的 host_key（非响应式读取）。
 * AI 的 run_ssh/sftp 工具可操作任意已配置主机；只有 host_key 与面板当前主机一致时，
 * 才允许把命令镜像进终端、触发文件树刷新，避免写错面板。
 */
function getCurrentHostKey(): string | null {
  const s = useHostStore.getState()
  return s.hosts.find((h) => h.id === s.currentHostId)?.hostKey ?? null
}

/**
 * 把后端历史消息（{role, parts:[...]}）转换为前端 ChatMessage 格式。
 *
 * parts wire 类型（见后端 MessageCodec）：text / reasoning / tool_call / tool_result /
 * context / interaction_confirm / interaction_ask。
 *
 * 还原规则（与实时 SSE 路径同一套 block 结构，保证历史与执行时看到的一致）：
 * - assistant parts 严格按落库顺序映射为时间线 blocks：思考、正文、工具行、交互卡；
 * - tool_result 存在独立的 tool 角色消息里，不单独成块，按 id 回填对应工具记录，
 *   isError=true 时工具行渲染失败红点；
 * - interaction_* 还原为「已决议、默认折叠」的确认/提问卡，可展开回看但不可再操作。
 */
function convertSessionMessages(msgs: SessionMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  // id → 工具记录（跨 assistant/tool 消息回填结果；id 全局唯一）。
  const recById = new Map<string, ToolCallRecord>()

  for (const m of msgs) {
    if (m.role === 'user') {
      const text = m.parts
        .filter((p): p is Extract<SessionMessage['parts'][number], { type: 'text' }> => p.type === 'text')
        .map((p) => p.text)
        .join('')
      out.push({ id: nextId('u'), role: 'user', text, time: Date.now() })
      continue
    }

    if (m.role === 'assistant') {
      const blocks: AssistantBlock[] = []
      const tools: ToolCallRecord[] = []
      let thinking = ''
      let text = ''
      for (const p of m.parts) {
        switch (p.type) {
          case 'text':
            text += p.text
            blocks.push({ kind: 'text', text: p.text })
            break
          case 'reasoning':
            thinking += p.text
            blocks.push({ kind: 'thinking', text: p.text, state: 'done' })
            break
          case 'context':
            blocks.push({ kind: 'context', name: p.name, summary: p.summary })
            break
          case 'tool_call': {
            // 结果（含成败状态）由随后的 tool 角色消息回填；回填前按完成态占位。
            const rec: ToolCallRecord = {
              id: p.id,
              toolName: p.toolName,
              input: p.input,
              output: undefined,
              isError: false,
              status: 'done',
            }
            tools.push(rec)
            recById.set(p.id, rec)
            blocks.push({ kind: 'tool', id: p.id })
            break
          }
          case 'interaction_confirm':
            blocks.push({
              kind: 'interaction',
              data: {
                confirmId: p.id,
                toolName: p.toolName,
                message: p.message,
                tags: p.tags,
                input: p.input,
                resolved: p.approved,
              },
            })
            break
          case 'interaction_ask':
            blocks.push({
              kind: 'interaction',
              data: {
                askId: p.id,
                question: p.question,
                hint: p.hint ?? undefined,
                options: p.options,
                resolvedAnswer: p.skipped === true ? null : (p.answer ?? ''),
              },
            })
            break
          default:
            break
        }
      }
      out.push({
        id: nextId('a'),
        role: 'assistant',
        text,
        thinking,
        thinkingState: 'done',
        pending: false,
        blocks,
        tools: tools.length > 0 ? tools : undefined,
      })
      continue
    }

    if (m.role === 'tool') {
      // tool 消息不直接渲染：把结果与成败回填到对应工具行。
      for (const p of m.parts) {
        if (p.type !== 'tool_result') continue
        const rec = recById.get(p.id)
        if (rec !== undefined) {
          rec.output = p.output
          rec.isError = p.isError === true
          rec.status = rec.isError ? 'error' : 'done'
        }
      }
    }
    // system 角色消息不渲染
  }
  return out
}

/** 把 ProviderProfile 列表组装成 ModelSelect 需要的 groups。
 * 规则：每个厂商先用「用户自定义目录 models」，空则用「内置流行 popular」。
 *       模型带 thinking 标记的挂 REASONING 档位。
 */
function buildGroups(providers: readonly ProviderProfile[]): ModelSelectGroup[] {
  return providers
    .filter(v => v.models.length > 0 || (v.popular?.length ?? 0) > 0)
    .map(v => {
      const source = v.models.length > 0 ? v.models : v.popular ?? []
      return {
        id: v.id,
        name: v.displayName,
        configured: v.configured,
        models: source.map(m => ({
          id: m.id,
          name: m.name ?? m.id,
          reasoning: m.thinking ? REASONING : undefined,
        })),
      }
    })
}

/**
 * 整轮 Working 总指示（dsh TurnStatus 1:1 复刻）：
 * - 渲染在对话流最后一行（所有消息之后、输入框之前），左对齐、跟随整个 running turn；
 * - 黑白流光文字（gradient + background-clip:text，墨色→灰→墨色，无 dot / 无插入符）；
 * - 运行 ≥15s 后追加耗时时钟（如 "Working… 2分05秒"）。
 */
function DeepDivingRow({ startTime }: { startTime: number | null }) {
  const [mountedAt] = useState(() => Date.now())
  // 锚定到 turn 开始时间：跨轮/中途刷新仍能保持真实已运行耗时。
  const anchor = startTime ?? mountedAt
  const [elapsedMs, setElapsedMs] = useState(() => Math.max(0, Date.now() - anchor))
  useEffect(() => {
    const tick = (): void => setElapsedMs(Math.max(0, Date.now() - anchor))
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [anchor])
  const showClock = elapsedMs >= 15_000
  return (
    <div className="dd-root" role="status" aria-live="polite">
      Working…
      {showClock && (
        <span className="dd-clock" aria-hidden>{formatRunDuration(elapsedMs)}</span>
      )}
    </div>
  )
}

/** 本地化耗时（dsh formatRunDuration）：≥1 分钟 → "2分05秒"，否则 "45秒"。 */
function formatRunDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return minutes > 0
    ? `${minutes}分${String(seconds).padStart(2, '0')}秒`
    : `${seconds}秒`
}

/**
 * turn 非 completed 闭合时的收尾提示气泡（停止/中断/出错）。
 * 投影器已按「空回复才提示 stopped」等规则给出 endNote；这里转成可渲染的 assistant 消息。
 */
function endNoteMessage(view: LiveTurnView): ChatMessage | null {
  const note = view.endNote
  if (!note) return null
  // error：走 AssistantMessage 的 TurnErrorRow（避免正文与错误行重复展示同一句）；
  // stopped / interrupted：作为普通收尾正文行渲染。
  if (note.tone === 'error') {
    return {
      id: `live-${view.turnNo}-end-note`,
      role: 'assistant',
      text: '',
      thinkingState: 'done',
      pending: false,
      error: { title: '本轮执行出错', message: view.errorMessage ?? note.text },
    }
  }
  return {
    id: `live-${view.turnNo}-end-note`,
    role: 'assistant',
    text: note.text,
    thinkingState: 'done',
    pending: false,
  }
}

/** 空态场景入口（点击即作为首轮指令发送）。 */
const HERO_SCENES: Array<{ label: string; icon: LucideIcon }> = [
  { label: '巡检这台主机', icon: ShieldCheck },
  { label: '查最近报错日志', icon: ScrollText },
  { label: '清理磁盘空间', icon: HardDrive },
  { label: '部署应用', icon: Rocket },
]

/**
 * 空态品牌标：终端提示符「>_」变体——青蓝渐变圆角方块，
 * 与侧栏 22px T 标同色同源，但更立体（48px + 品牌色柔光投影）。
 */
function HeroBrand() {
  return (
    <span className="conv-heroBrand" aria-hidden="true">
      <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
        <defs>
          <linearGradient id="tmHeroBrand" x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
            <stop offset="0" style={{ stopColor: 'var(--tm-brand-primary)' }} />
            <stop offset="1" style={{ stopColor: 'var(--tm-brand-strong)' }} />
          </linearGradient>
        </defs>
        <rect width="48" height="48" rx="13" fill="url(#tmHeroBrand)" />
        <circle
          cx="24"
          cy="24.4"
          r="7.2"
          fill="none"
          stroke="var(--tm-brand-on)"
          strokeWidth="3.2"
        />
        <path
          d="M31.2 15.6 V33.2"
          stroke="var(--tm-brand-on)"
          strokeWidth="3.2"
          strokeLinecap="round"
        />
        <circle
          className="aye-brand-pupil"
          cx="24"
          cy="24.4"
          r="2.4"
          fill="var(--tm-brand-on)"
        />
      </svg>
    </span>
  )
}

export default function ConversationPage() {
  const navigate = useNavigate()
  const { sessionId } = useParams<{ sessionId?: string }>()
  /**
   * ?new=1：显式「新会话」标记。仅靠无 sessionId 无法区分「点新会话」与「点对话导航」，
   * 因此新会话按钮带该参数；进入空白态后立即用 replace 抹掉（不污染后退栈）。
   */
  const [searchParams, setSearchParams] = useSearchParams()

  const [draft, setDraft] = useState('')
  /** 历史基线消息（GET messages，仅挂载/sessionId 变化时拉取；turn 结束后不重拉）。 */
  const [messages, setMessages] = useState<ChatMessage[]>([])
  /** 历史会话消息加载中：composer 先隐藏（dsh settling），避免空 hero 闪一下再跳底部。 */
  const [restoring, setRestoring] = useState(false)

  /**
   * 本页发送但当前基线尚未确认的用户行（startTurn 成功即插入；基线拉取命中同文本后移除）。
   * 用户行不经过 live 投影（runAgent 会把它直接写入旧 messages 表，投影会重复）。
   */
  const [optimistic, setOptimistic] = useState<OptimisticMessage[]>([])

  /** live turn 单一真值：running 态、草稿、交互卡、终态提示全部来自 runtime 事件投影。 */
  const liveTurn = useLiveTurn(sessionId)
  const running = !!sessionId && liveTurn?.phase === 'running'
  /**
   * startTurn 请求在途（202 已返回到 turn/start 事件到达前有一个短窗口 running 仍为 false），
   * 本地再挡一道重复发送；服务端 409 SESSION_BUSY 是最终防线。
   */
  const [startingTurn, setStartingTurn] = useState(false)

  /** 当前选中的项目（工作区）：一键部署流程的前提。 */
  const [workspace, setWorkspace] = useState<Workspace | null>(null)
  const [pickerOpen, setPickerOpen] = useState(false)

  /**
   * 部署模式（显式开启：火箭钮 / 输入 /部署 / 一键部署建议条）。
   * 开启后每轮请求带 deploy=true，后端才注入 7 阶段流水线提示词；
   * verify 完成后自动退出，也可用模式条上的 × 手动退出。切会话复位。
   */
  const [deployMode, setDeployMode] = useState(false)
  /** B 方案：显式选中项目后出现一次的「一键部署」建议条；用掉/关掉后不再出现。 */
  const [deployHint, setDeployHint] = useState(false)

  // 切会话复位部署态（绑定项目由下方 effect 从后端回填，回填不弹建议条）。
  useEffect(() => {
    setDeployMode(false)
    setDeployHint(false)
  }, [sessionId])

  // 工作区绑定：切换会话先清空旧选择（避免新会话瞬间显示上个会话的项目），
  // 再从后端回填该会话持久化的绑定（刷新/重启后仍在）。
  useEffect(() => {
    if (!sessionId) {
      setWorkspace(null)
      return
    }
    let cancelled = false
    setWorkspace(null)
    void (async () => {
      try {
        const ws = await getBoundWorkspace(sessionId)
        if (!cancelled && ws) setWorkspace(ws)
      } catch {
        /* 绑定查询失败不影响对话 */
      }
    })()
    return () => { cancelled = true }
  }, [sessionId])

  /** 执行模式（trusted/standard/paranoid 三档，对话内可切换，影响后端工具权限）。 */
  const [mode, setMode] = useState<ExecutionMode>('write_confirm')

  /** 历史会话列表（全局 store，侧栏会话树与本页共用）。 */
  const sessions = useSessionsStore((s) => s.sessions)
  const sessionsLoading = useSessionsStore((s) => s.loading)
  const loadSessions = useSessionsStore((s) => s.load)

  /** 当前选中的服务器（用于让 AI 通过 SFTP 工具读写远程文件）。 */
  const currentHost = useHostStore(selectCurrentHost)
  const currentState = useHostStore(selectCurrentState)
  const currentStatus = currentState?.status || 'disconnected'
  const disconnectHost = useHostStore((s) => s.disconnect)
  const reconnectHost = useHostStore((s) => s.reconnect)

  /** 右侧工作台面板（对齐 v1）：终端/文件 Tab + 可见性 + 宽度（默认 34%，可拖拽）。
   *  未连接时默认收起，连接成功后自动展开（见下方状态边沿 effect）。 */
  const [wbTab, setWbTab] = useState<'terminal' | 'files'>('terminal')
  const [rightVisible, setRightVisible] = useState(false)
  const [rightWidth, setRightWidth] = useState(0)
  const [isMobile, setIsMobile] = useState(false)
  const layoutRef = useRef<HTMLDivElement | null>(null)
  const terminalPanelRef = useRef<SshTerminalPanelRef | null>(null)
  const fileTreeRef = useRef<ServerFileTreeRef | null>(null)
  /** sftp 连续多次变更合并为一次去抖刷新（force 拉取，绕过目录列表缓存）。 */
  const treeRefreshTimerRef = useRef<number | null>(null)
  /** 面板从隐藏展开后待执行的文件树定位（等 ServerFileTree 挂载）。 */
  const pendingRevealRef = useRef<string | null>(null)

  const scheduleTreeRefresh = useCallback(() => {
    if (treeRefreshTimerRef.current !== null) window.clearTimeout(treeRefreshTimerRef.current)
    treeRefreshTimerRef.current = window.setTimeout(() => {
      treeRefreshTimerRef.current = null
      fileTreeRef.current?.refresh()
    }, 400)
  }, [])

  /**
   * runtime live 事件 → 右侧面板联动（仅收到订阅之后的新事件；历史快照不投递）。
   * tool/call 已自带 name/input/hostKey，tool/result 自带 hostKey，无需本地缓存。
   * 只镜像与右侧当前主机 hostKey 一致的操作，避免写错面板。
   */
  const mirrorRuntimeEvent = useCallback((ev: SessionEvent): void => {
    const d = ev.data ?? {}
    const eventHost = typeof d.hostKey === 'string' && d.hostKey.length > 0
      ? d.hostKey
      : hostKeyOf(d.arguments)
    const sameHost = eventHost != null && eventHost === getCurrentHostKey()

    if (ev.type === ET.TOOL_CALL) {
      const name = String(d.name ?? '')
      if ((name === 'run_ssh' || name === 'run_ssh_async') && sameHost) {
        const command = typeof d.arguments?.command === 'string' ? d.arguments.command : ''
        const panel = terminalPanelRef.current
        panel?.writeAiCommand(command.slice(0, 2000))
        if (name === 'run_ssh_async') {
          panel?.writeAiOutput('异步任务已启动（后台执行），进度与结果见对话中的轮询卡片')
        }
      }
      return
    }

    if (ev.type === ET.TOOL_RESULT) {
      const name = String(d.name ?? '')
      if (name === 'run_ssh' && sameHost) {
        const panel = terminalPanelRef.current
        if (panel) {
          const { exit, body } = parseSshResult(resultOutputText(d.output))
          if (body.trim().length > 0) panel.writeAiOutput(clampForTerminal(body))
          panel.writeAiResult(exit ?? (d.isError === true ? 1 : 0))
        }
      } else if (isMutationTool(name) && d.isError !== true && sameHost) {
        // 写/改/删/传/移动/建目录成功 → 去抖强制刷新当前文件树
        scheduleTreeRefresh()
      }
    }
  }, [scheduleTreeRefresh])

  // 上传任务结束（成功/失败/取消）边沿：刷新文件树 + 终端回显流水
  const prevDoneRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    const unsub = useUploadStore.subscribe((state) => {
      const sid = selectSessionId(useHostStore.getState())
      // 只处理当前 SSH 会话的任务：多主机并行上传时不刷错文件树、不写错终端
      const settled = state.tasks.filter(
        (t) => ['done', 'error', 'canceled'].includes(t.status) && t.sessionId === sid,
      )
      const nowAll = new Set(state.tasks
        .filter((t) => ['done', 'error', 'canceled'].includes(t.status))
        .map((t) => t.id))
      let fresh = 0
      for (const t of settled) if (!prevDoneRef.current.has(t.id)) fresh += 1
      if (fresh > 0) {
        scheduleTreeRefresh()
        for (const t of settled) {
          if (!prevDoneRef.current.has(t.id)) {
            terminalPanelRef.current?.writeUploadNote(
              t.status === 'done'
                ? `[上传] ${t.name} → ${t.remoteDir}`
                : `[上传未完成] ${t.name}：${t.status}`,
            )
          }
        }
      }
      prevDoneRef.current = nowAll
    })
    return unsub
  }, [scheduleTreeRefresh])

  // 右侧面板由隐藏切到展开：补做挂起的「在文件树定位」。
  useEffect(() => {
    if (!rightVisible || !pendingRevealRef.current) return
    const path = pendingRevealRef.current
    pendingRevealRef.current = null
    const t = window.setTimeout(() => {
      const ok = fileTreeRef.current?.revealFile(path)
      if (!ok) antdMessage.warning('请先在右侧连接对应主机，再定位远程文件')
    }, 200)
    return () => window.clearTimeout(t)
  }, [rightVisible])

  // 响应式：初始化右侧宽度，窗口变窄/移动端自动收起右侧面板。
  // 桌面端不再强制展开——可见性交给连接状态（未连接收起 / 连上自动展开）与用户手动开关。
  useEffect(() => {
    const updateLayout = () => {
      const width = window.innerWidth
      const mobile = width < 768
      setIsMobile(mobile)
      if (mobile) {
        setRightVisible(false)
      } else if (rightWidth === 0) {
        const initial = Math.max(320, Math.floor(width * 0.34))
        setRightWidth(initial)
      }
    }
    updateLayout()
    window.addEventListener('resize', updateLayout)
    return () => window.removeEventListener('resize', updateLayout)
  }, [rightWidth])

  /**
   * 连接成功边沿：disconnected/error/connecting → connected 时桌面端自动展开工作台。
   * 只在状态跳变沿触发一次；用户随后手动收起不会被弹回（同一 connected 状态内无效果）。
   * ref 初值按未连接处理：挂载时主机已连接（切页回来/自动重连完成）也会展开。
   */
  const prevStatusRef = useRef<string>('disconnected')
  useEffect(() => {
    const prev = prevStatusRef.current
    prevStatusRef.current = currentStatus
    if (prev !== 'connected' && currentStatus === 'connected' && !isMobile) {
      setRightVisible(true)
    }
  }, [currentStatus, isMobile])

  const handleResize = useCallback((delta: number) => {
    setRightWidth((prev) => {
      const containerWidth = layoutRef.current?.clientWidth || window.innerWidth
      const minW = 320
      const maxW = Math.floor(containerWidth * 0.6)
      return Math.max(minW, Math.min(maxW, prev - delta))
    })
  }, [])

  const toggleRightPanel = useCallback(() => {
    setRightVisible((v) => {
      const next = !v
      if (next && rightWidth === 0) {
        const containerWidth = layoutRef.current?.clientWidth || window.innerWidth
        setRightWidth(Math.max(320, Math.floor(containerWidth * 0.34)))
      }
      return next
    })
  }, [rightWidth])

  /** 右侧工作台连接/断开按钮。 */
  const handleConnectAction = useCallback(async () => {
    if (!currentHost?.id) return
    const id = currentHost.id
    if (currentStatus === 'connected') {
      await disconnectHost(id)
    } else {
      // 未连接/异常：有缓存凭据自动重连，否则置异常态由终端区连接卡片引导输密码。
      await reconnectHost(id)
    }
  }, [currentHost, currentStatus, disconnectHost, reconnectHost])

  // 厂商目录数据 + 加载态
  const [providers, setProviders] = useState<ProviderProfile[]>([])
  const [providersLoading, setProvidersLoading] = useState(true)
  /** 是否已做过「默认选中项 vs 已加载目录」的回显校准，仅做一次，避免覆盖用户手动选中。 */
  const defaultAdjustedRef = useRef(false)

  const [current, setCurrent] = useState<ModelSelection>(() =>
    getStoredModelSelection() ?? {
      provider: 'deepseek',
      model: 'deepseek-v4-pro',
      reasoningEffort: 'high',
    },
  )

  const flowRef = useRef<HTMLDivElement | null>(null)
  /**
   * 贴底跟随标记：只有用户（或新发送）本来就停在底部附近时，新内容才自动滚到底。
   * 用户上翻看历史时绝不抢滚动；同时避免流式期间每 token 无意义的强制回流。
   */
  const stickToBottomRef = useRef(true)
  const handleFlowScroll = useCallback((): void => {
    const el = flowRef.current
    if (el === null) return
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight
    stickToBottomRef.current = dist < 48
  }, [])
  /** 「选择项目」入口 chip，WorkspacePicker 弹层锚定它向上展开。 */
  const wsTriggerRef = useRef<HTMLButtonElement | null>(null)
  /** 标记本次 URL 变更是由 startTurn 返回后回填 sessionId 触发的，跳过一次基线加载（避免空 hero settling 闪烁）。 */
  const selfNavigatedRef = useRef(false)
  /**
   * 用户显式点了「新会话」：停留在空白态。参数 ?new=1 抹除后本 effect 会再跑一次，
   * 用该标记拦住「恢复上次会话」分支；进入具体会话（sessionId 出现）后复位。
   */
  const freshSessionRef = useRef(false)

  // 挂载时拉取已配置厂商（Settings → Models 页写入的真数据）。
  useEffect(() => {
    let cancelled = false
    ;(async (): Promise<void> => {
      setProvidersLoading(true)
      try {
        const list = await listConfiguredProviders()
        if (cancelled) return
        setProviders(list)
      } catch (err) {
        if (cancelled) return
        // 后端不可达：模型目录留空（下拉无可用项），提示用户启动后端后刷新。
        setProviders([])
        antdMessage.warning({
          content: `后端不可达（${errorMessage(err)}），请启动 Aye Server 后刷新页面以读取模型配置。`,
          duration: 8,
        })
      } finally {
        if (!cancelled) setProvidersLoading(false)
      }
    })()
    return () => { cancelled = true }
  }, [])

  // 目录：providers → groups（跟随 providers loading 完成后稳定）。
  const groups: ModelSelectGroup[] = useMemo(() => buildGroups(providers), [providers])

  // 默认选中项回显校准（经验 1807893）：
  //   providers 首次加载完成后，一次性校验 current.provider/current.model 是否存在于 groups。
  //   若不存在 → 回退 groups[0].models[0] 并 antd 告警。
  //   「仅校准一次」，之后用户手动选择不再被覆盖。
  useEffect(() => {
    if (providersLoading) return
    if (defaultAdjustedRef.current) return
    if (groups.length === 0) return
    defaultAdjustedRef.current = true

    const grp = groups.find(g => g.id === current.provider)
    const modelExists = grp !== undefined && grp.models.some(m => m.id === current.model)
    if (modelExists) return

    const fallbackGrp = groups[0]
    const fallbackModel = fallbackGrp.models[0]
    if (fallbackModel === undefined) return
    const fallbackReasoning = fallbackModel.reasoning?.defaultEffort
    const fallbackSelection: ModelSelection =
      fallbackReasoning === undefined
        ? { provider: fallbackGrp.id, model: fallbackModel.id }
        : { provider: fallbackGrp.id, model: fallbackModel.id, reasoningEffort: fallbackReasoning }

    antdMessage.warning(
      `默认模型 (${current.provider}/${current.model}) 在当前可用目录中不存在，已自动切换为 ` +
      `${fallbackGrp.name} / ${fallbackModel.name ?? fallbackModel.id}。`,
      6,
    )
    setCurrent(fallbackSelection)
    setStoredModelSelection(fallbackSelection)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [providersLoading])

  // 新消息 / 生成中自动滚到底部（依赖拼接后的展示列表）。
  const displayMessages = useMemo<ChatMessage[]>(() => {
    const opts = optimistic
      .filter((o) => (o.__optSid ?? null) === (sessionId ?? null))
    const liveMsgs: ChatMessage[] = liveTurn?.stepMessages ?? []
    const note = liveTurn ? endNoteMessage(liveTurn) : null
    return note !== null
      ? [...messages, ...opts, ...liveMsgs, note]
      : [...messages, ...opts, ...liveMsgs]
  }, [messages, optimistic, sessionId, liveTurn])

  /**
   * 待办码头数据：跨全会话可见消息回放 todo_write，取最后一次 replace 快照。
   * 历史基线 + 乐观行 + live 草稿都在 displayMessages 内，live 期间随 rAF 合流刷新。
   */
  const standingTodo = useMemo(() => standingTodoFromMessages(displayMessages), [displayMessages])

  /**
   * 部署阶段快照：回放全会话 deploy_stage 调用得到固定 7 阶段状态。
   * 与部署模式开关无关——AI 即便靠自然语言走进部署，阶段卡也照常出现。
   */
  const standingDeploy = useMemo(() => standingDeployFromMessages(displayMessages), [displayMessages])

  /** 部署进行中：任一阶段处于 running——火箭钮在此期间持续推力震动循环。 */
  const deployRunning = useMemo(
    () => !!standingDeploy?.some(s => s.status === 'running'),
    [standingDeploy],
  )

  // verify 完成 → 自动退出部署模式（失败不退出，留在模式内修复重试）。
  useEffect(() => {
    if (deployMode && deployIsFinished(standingDeploy)) setDeployMode(false)
  }, [deployMode, standingDeploy])

  // 新内容到达：仅在贴底时跟随滚动（用户上翻阅读时保持原位；合流后最多每帧一次）。
  useEffect(() => {
    const el = flowRef.current
    if (el !== null && stickToBottomRef.current) el.scrollTop = el.scrollHeight
  }, [displayMessages])

  // 历史会话列表由全局 store 提供（侧栏会话树也会触发加载，store 内短路去重）。
  useEffect(() => {
    void loadSessions()
  }, [loadSessions])

  /**
   * live 视图跨轮跟踪，承担两件事：
   * 1) 新轮 turn/start 让投影器复位时，把上一 ended 轮的「用户行 + 助手定稿」
   *    一起固化进本地基线——用户行经 turnUserMapRef 按 turnNo 精确关联（仅本挂载
   *    发出的轮有映射），刷新恢复的轮其用户行已在服务端基线则只固化助手；
   *    本挂载不重拉 GET messages（避免与 live 视图竞态/重复），真正的重挂载/刷新
   *    仍以服务端基线为准（assistant 投影仅在 turn 闭合后落旧表）。
   * 2) running→ended 边沿刷新会话树（标题/更新时间可能已变）。
   */
  const sealedTurnsRef = useRef<Set<number>>(new Set())
  const prevLiveViewRef = useRef<LiveTurnView | null>(null)
  /**
   * 本挂载内 turnNo → 触发该轮的乐观用户行 id。
   * 仅记录本挂载内发出的轮；刷新/重挂载后从 snapshot 恢复的在跑轮不占映射
   * （其用户行已由 runAgent 写入服务端 messages 基线）。
   */
  const turnUserMapRef = useRef<Map<number, string>>(new Map())
  useEffect(() => {
    // 切会话：跨轮跟踪状态按会话隔离（不同会话 turnNo 都可能从 1 开始）。
    sealedTurnsRef.current.clear()
    prevLiveViewRef.current = null
    turnUserMapRef.current.clear()
  }, [sessionId])
  // useLayoutEffect：固化与投影器 reset 在同一次提交内完成，
  // 浏览器绘制前基线已补齐，避免旧轮内容闪空一帧。
  useLayoutEffect(() => {
    const prev = prevLiveViewRef.current
    prevLiveViewRef.current = liveTurn
    const sid = sessionId ?? null

    // running 轮首次进入视图：把本挂载内尚未归属的最旧一条同会话乐观行关联给该轮。
    // 新会话首条消息的 __optSid 在 startTurn 返回后才回填，随后续 live 发布也能补上。
    if (liveTurn?.phase === 'running' && !turnUserMapRef.current.has(liveTurn.turnNo)) {
      const assigned = new Set(turnUserMapRef.current.values())
      const candidate = optimistic.find(
        (o) => (o.__optSid ?? null) === sid && !assigned.has(o.id),
      )
      if (candidate) turnUserMapRef.current.set(liveTurn.turnNo, candidate.id)
    }

    if (prev && liveTurn && prev.turnNo !== liveTurn.turnNo
      && prev.phase === 'ended' && !sealedTurnsRef.current.has(prev.turnNo)) {
      sealedTurnsRef.current.add(prev.turnNo)
      const sealed: ChatMessage[] = []

      // 该轮乐观用户行随助手定稿一起固化进基线，保持「用户→助手」顺序；
      // 找不到（刷新恢复的轮、冷恢复 interrupted 轮）说明用户行已在服务端基线，
      // 只固化助手行，绝不能按文本/位置兜底误捞属于其他轮的乐观行。
      const userOptId = turnUserMapRef.current.get(prev.turnNo)
      const userOpt = userOptId
        ? optimistic.find((o) => o.id === userOptId && (o.__optSid ?? null) === sid)
        : undefined
      if (userOpt) {
        sealed.push({
          id: `sealed-${prev.turnNo}-user`,
          role: 'user',
          text: userOpt.text,
          time: userOpt.time,
          attachments: userOpt.attachments,
          fileTags: userOpt.fileTags,
        })
        setOptimistic((prevOpts) => prevOpts.filter((o) => o.id !== userOpt.id))
        turnUserMapRef.current.delete(prev.turnNo)
      }

      sealed.push(...prev.stepMessages.map(m => ({
        ...m,
        id: `sealed-${prev.turnNo}-s${m.stepNo}`,
      })))
      const note = endNoteMessage(prev)
      if (note) sealed.push(note)
      setMessages(prevMsgs => [...prevMsgs, ...sealed])
    }
    if (prev?.phase === 'running' && liveTurn?.phase === 'ended') void loadSessions(true)
  }, [liveTurn, loadSessions, optimistic, sessionId])

  /**
   * 订阅当前会话的运行时（Agent 生命周期与 HTTP 连接解耦的核心）：
   * - 挂载/切换会话即 attach，卸载只 detach；turn 在后台继续运行，不会被中止；
   * - 订阅挂载之后的 live 事件做终端/文件树联动（历史快照不镜像）。
   */
  useEffect(() => {
    if (!sessionId) return
    attachSessionRuntime(sessionId)
    const off = addRuntimeEventListener(sessionId, mirrorRuntimeEvent)
    return () => {
      off()
      detachSessionRuntime(sessionId)
    }
  }, [sessionId, mirrorRuntimeEvent])

  /**
   * 挂载补刷（D7）：重新进入一个已在运行的会话时，首帧快照里若已含变更类工具结果
   * （切走期间 AI 改了文件），补刷一次文件树。live 期间的变更由事件联动去抖处理。
   */
  const bootTreeRefreshedRef = useRef(false)
  useEffect(() => {
    bootTreeRefreshedRef.current = false
  }, [sessionId])
  useEffect(() => {
    if (!sessionId || bootTreeRefreshedRef.current) return
    if (!liveTurn) return
    bootTreeRefreshedRef.current = true
    if (liveTurn.lastMutationAt !== undefined) scheduleTreeRefresh()
  }, [sessionId, liveTurn, scheduleTreeRefresh])

  // URL sessionId 变化时加载该会话的历史消息（基线）；无 sessionId 则清空（新会话）。
  // 自导航（startTurn 返回回填 ID）时跳过：新会话基线必为空，避免 composer settling 闪烁；
  // live 草稿与乐观用户行不受基线影响，无需「防止覆盖流式内容」。
  useEffect(() => {
    if (selfNavigatedRef.current) {
      selfNavigatedRef.current = false
      return
    }
    if (!sessionId) {
      setMessages([])
      setOptimistic([])
      setRestoring(false)
      return
    }
    let cancelled = false
    // 切换/进入会话恢复贴底（沿用上翻标记会让新打开的会话不跟随首屏内容）。
    stickToBottomRef.current = true
    setRestoring(true)
    ;(async () => {
      try {
        const r = await sessionsApi.messages(sessionId)
        if (cancelled) return
        const baseline = convertSessionMessages(r.messages ?? [])
        setMessages(baseline)
        // 基线已落库的用户行按多重集合逐个消耗乐观行（兼容连续发送相同文本）。
        const userTexts = baseline.filter((m) => m.role === 'user').map((m) => m.text)
        if (userTexts.length > 0) {
          setOptimistic((prev) => {
            const remain = [...prev]
            for (const text of userTexts) {
              const idx = remain.findIndex((o) => o.__optSid === sessionId && o.text === text)
              if (idx >= 0) remain.splice(idx, 1)
            }
            return remain
          })
        }
      } catch (e) {
        console.error('Failed to load session messages:', e)
        if (!cancelled) setMessages([])
      } finally {
        if (!cancelled) setRestoring(false)
      }
    })()
    return () => { cancelled = true }
  }, [sessionId])

  /**
   * 进入 /chat（无 sessionId）时的默认行为（对齐主流聊天产品）：
   *  - 带 ?new=1（点了「新会话」）→ 停留空白态，replace 抹掉参数；
   *  - 否则恢复上次打开的会话（localStorage 记录，且必须仍存在于历史列表）；
   *  - 没有上次会话 / 历史已删除 → 停留空白态。
   * 这样刷新 / 点侧栏「对话」标签 / 重开浏览器都能回到原会话继续聊。
   */
  useEffect(() => {
    if (sessionId) {
      // 已进入具体会话（含新会话发出首条消息后回填 ID）：解除「显式新开」锁定。
      freshSessionRef.current = false
      return
    }
    if (sessionsLoading) return
    if (searchParams.get('new') === '1') {
      setMessages([])
      freshSessionRef.current = true
      const next = new URLSearchParams(searchParams)
      next.delete('new')
      setSearchParams(next, { replace: true })
      return
    }
    // 本次空白态是用户显式新开的（参数刚被抹除，effect 重跑会走到这里）：保持空白。
    if (freshSessionRef.current) return
    const lastId = getLastSessionId()
    if (lastId && sessions.some((s) => s.id === lastId)) {
      navigate(`/chat/${lastId}`, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, sessionsLoading, sessions, searchParams])

  /** ModelSelect 的选择校验：选择「未配置 API Key」的厂商时拒绝选中（toast 展示）。 */
  const selectModel = useCallback(async (selection: ModelSelection): Promise<boolean> => {
    const vendor = providers.find(v => v.id === selection.provider)
    if (vendor !== undefined && !vendor.configured) return false
    setCurrent(selection)
    setStoredModelSelection(selection)
    return true
  }, [providers])

  /**
   * 发送一条用户消息。载荷来自 InputBar：本地附件（已上传）与服务器文件 @path 标签。
   * 服务器文件标签以 `@path` 追加进消息文本（对齐 v1 的插槽语义，AI 据 path 走 SFTP 工具读写）。
   *
   * 方案 C：只负责 POST /api/v2/agent/turns 启动 turn；运行态与所有渲染由 runtime 事件投影驱动，
   * 切走/断线/刷新都不影响 Agent 执行。用户行做乐观展示，基线重拉命中后消耗移除。
   */
  const send = (
    payload: ComposerSubmitPayload,
    opts?: { deploy?: boolean },
  ): void => {
    const rawText = payload.text.trim()

    if (running || startingTurn) return

    // 特殊操作：/部署（/deploy）斜杠命令只切换部署模式，不产生对话消息。
    // InputBar 在 onSubmit 后会自行清空输入框。
    if (rawText === '/部署' || rawText === '/deploy') {
      if (deployMode) {
        setDeployMode(false)
      } else if (!workspace) {
        antdMessage.warning('请先选择项目，再进入部署模式')
        setPickerOpen(true)
      } else {
        setDeployHint(false)
        setDeployMode(true)
      }
      return
    }

    const text = rawText
    const wantDeploy = opts?.deploy === true || deployMode
    const tagLines = payload.fileTags.map((t) => `@${t.path}`)
    const finalText = [text, ...tagLines].filter(Boolean).join('\n\n')
    const hasAnything = finalText.length > 0 || payload.attachments.length > 0
    if (!hasAnything) return

    const modelRef = `${current.provider}::${current.model}`
    const preSid = sessionId ?? null
    const userMsg: OptimisticMessage = {
      id: nextId('u'), role: 'user', text: finalText, time: Date.now(),
      attachments: payload.attachments, fileTags: payload.fileTags,
      __optSid: preSid,
    }
    setOptimistic(prev => [...prev, userMsg])
    setDraft('')
    // 主动发送强制贴底：即使用户之前上翻看过历史，新轮也要跟到最新内容。
    stickToBottomRef.current = true
    setStartingTurn(true)

    void (async () => {
      try {
        const res = await startTurn({
          sessionId: sessionId ?? undefined,
          message: finalText,
          modelRef,
          hostId: currentHost?.id,
          mode,
          attachments: payload.attachments,
          workspaceId: workspace?.id,
          deploy: wantDeploy,
        })
        // 新会话拿到后端 ID 后同步地址栏（replace 不污染后退栈）；attach 由 sessionId effect 承担。
        if (!sessionId) {
          setOptimistic(prev => prev.map(o => o.id === userMsg.id ? { ...o, __optSid: res.sessionId } : o))
          selfNavigatedRef.current = true
          setLastSessionId(res.sessionId)
          navigate(`/chat/${res.sessionId}`, { replace: true })
          void loadSessions(true)
        }
      } catch (e: any) {
        // 启动失败：回滚乐观行，展示后端错误（409 会话忙/422 模型未配置/503 执行池满等）。
        antdMessage.error(e?.message ? String(e.message) : '启动失败，请重试')
        setOptimistic(prev => prev.filter(o => o.id !== userMsg.id))
      } finally {
        setStartingTurn(false)
      }
    })()
  }

  /**
   * send 闭包依赖大量渲染态（running/sessionId/current/mode…），每帧都是新引用。
   * 用 ref 暴露最新实现，让传给 memo 子组件（InputBar/AssistantMessage）的回调
   * 保持永久稳定——否则这些内联闭包会击穿所有历史消息的 memo，流式期间每个
   * token 都把整页 Markdown 重新解析一遍（页面卡死的主因之一）。
   */
  const sendRef = useRef(send)
  sendRef.current = send
  const handleSubmit = useCallback((payload: ComposerSubmitPayload): void => {
    sendRef.current(payload)
  }, [])
  const handleResumeTurn = useCallback((): void => {
    sendRef.current({ text: '已连接，请继续刚才的任务', attachments: [], fileTags: [] })
  }, [])

  /**
   * 进入部署模式：必须先绑定项目，否则引导打开项目选择器。
   * 火箭钮、/部署 斜杠、一键部署建议条共用此入口。
   */
  const enterDeployMode = useCallback((): boolean => {
    if (!workspace) {
      antdMessage.warning('请先选择项目，再进入部署模式')
      setPickerOpen(true)
      return false
    }
    setDeployHint(false)
    setDeployMode(true)
    return true
  }, [workspace])

  const toggleDeployMode = useCallback((): void => {
    if (deployMode) setDeployMode(false)
    else enterDeployMode()
  }, [deployMode, enterDeployMode])

  /** B 方案建议条：进入部署模式并立即发出确定性部署指令（同事件内显式带 deploy）。 */
  const startQuickDeploy = useCallback((): void => {
    if (!enterDeployMode()) return
    setDeployHint(false)
    sendRef.current(
      { text: '部署当前项目', attachments: [], fileTags: [] },
      { deploy: true },
    )
  }, [enterDeployMode])
  /**
   * 停止：请求后端取消当前 turn（幂等，无运行 turn 返回 ok=false）。
   * 不在本地置态——终态以 turn/end(reason=aborted) 事件投影为准，保证多标签/断线恢复一致。
   */
  const stop = useCallback((): void => {
    const id = sessionId
    if (!id) return
    void cancelTurn(id).catch((e) => {
      console.error('cancel turn failed:', e)
    })
  }, [sessionId])

  /**
   * 工具卡/产物卡里的远程路径点击：切到文件 Tab，在右侧文件树中定位
   * （跳父目录 + 高亮该行；文件双击即可进编辑器）。面板隐藏时先展开再补定位。
   */
  const openPath = useCallback((path: string): void => {
    setWbTab('files')
    if (!rightVisible) {
      pendingRevealRef.current = path
      setRightVisible(true)
      return
    }
    const ok = fileTreeRef.current?.revealFile(path)
    if (!ok) antdMessage.warning('请先在右侧连接对应主机，再定位远程文件')
  }, [rightVisible])

  // 空态 = 无历史基线、无乐观用户行、无 live 草稿（发送瞬间即离开 hero）。
  const empty = displayMessages.length === 0

  /**
   * 当前等待用户处理的交互（dsh composer 接管链 conversation.composer）：单一真值来自 live 投影。
   * 存在期间底部 composer 座由审批/提问浮卡整体接管；卡片组件自行调用 resolve API，
   * interaction/resolved 事件清掉 active，InputBar 回来；本轮异常闭合时未处理的交互
   * 在时间线置灰保留（投影器已把 resolved/resolvedAnswer 写进内联卡 data）。
   */
  const activeInteraction = useMemo<
    | { id: string; kind: 'confirm'; data: ConfirmationCardData }
    | { id: string; kind: 'ask'; data: AskCardData }
    | null
  >(() => {
    const a = liveTurn?.activeInteraction
    if (!a) return null
    // kind 与 data 由投影器按 interaction/requested 载荷成对构造（kind 与 data 字段天然对应）。
    return a.kind === 'confirm'
      ? { id: a.interactionId, kind: 'confirm', data: a.data as ConfirmationCardData }
      : { id: a.interactionId, kind: 'ask', data: a.data as AskCardData }
  }, [liveTurn])

  // 模型座在 providers 仍在拉取时灰化不可点，避免用户点空导致 undefined 选择。
  const modelSelectDimmed = providersLoading

  /**
   * 输入框插槽用 useMemo 钉住引用：InputBar 是 memo 组件，
   * 内联新建的元素对象每帧引用都不同，会击穿 memo 让整条输入栏随流式重渲染。
   * 执行模式：dsh 风格盾牌下拉（只读 / 写操作确认 / 全部确认）+ 模型座，挂在右下工具槽。
   */
  const composerTrailing = useMemo(
    () => (
      <div className="conv-trailing" style={{ opacity: modelSelectDimmed ? 0.6 : 1, pointerEvents: modelSelectDimmed ? 'none' : 'auto' }}>
        <PermissionModeSelect value={mode} onChange={setMode} disabled={modelSelectDimmed} />
        <ModelSelect groups={groups} current={current} select={selectModel} />
      </div>
    ),
    [modelSelectDimmed, mode, groups, current, selectModel],
  )

  /**
   * 左下工具槽：部署模式火箭钮 + 绑定项目后的迷你入口（大胶囊此时坍缩为这个 28px 图标，
   * 点击仍可打开选择器切换/解绑）。useMemo 钉引用，避免击穿 InputBar memo。
   */
  const composerTools = useMemo(
    () => (
      <>
        <button
          type="button"
          className={`inp-deploy${deployMode ? ' is-active' : ''}${deployRunning ? ' is-deploying' : ''}`}
          aria-label="部署模式"
          aria-pressed={deployMode}
          title={deployRunning ? '部署进行中…' : '部署模式（也可直接输入 /部署）'}
          onMouseDown={(e) => { e.preventDefault() }}
          onClick={toggleDeployMode}
        >
          <DeployRocketIcon size={14} />
        </button>
        {workspace && (
          <button
            ref={wsTriggerRef}
            type="button"
            className={`wp-mini${pickerOpen ? ' is-open' : ''}`}
            aria-haspopup="menu"
            aria-expanded={pickerOpen}
            title={`项目：${workspace.canonical_path}（点击切换或解绑）`}
            onMouseDown={(e) => { e.preventDefault() }}
            onClick={() => setPickerOpen(true)}
          >
            <FolderOpen size={15} strokeWidth={1.8} />
          </button>
        )}
      </>
    ),
    [deployMode, deployRunning, workspace, pickerOpen, toggleDeployMode],
  )

  return (
    <div className="conv-root">
      <div className="conv-main">
        {/* 顶部条：终端/文件面板开关。
            主机/会话身份与会话操作（删除等）全部收敛到左侧会话树。 */}
        <div className="conv-topbar">
          {!rightVisible && !isMobile && (
            <button
              type="button"
              className="dsh-icon-btn"
              title="打开终端/文件面板"
              onClick={toggleRightPanel}
            >
              <FxNavIcon cls="pn-ico pn-open-r">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <rect width="18" height="18" x="3" y="3" rx="2" />
                  <path className="pn-rail" d="M15 3v18" />
                  <path className="pn-chv" d="m10 15-3-3 3-3" />
                </svg>
              </FxNavIcon>
            </button>
          )}
        </div>

        {/* 左右分栏工作台（对齐 v1）：左 AI 对话 + 右 终端/文件 Tab */}
        <div ref={layoutRef} className={`wb-layout ${rightVisible ? '' : 'wb-layout-right-hidden'}`}>
          {/* 左：AI 对话。dsh 机制：输入框单实例常驻滚动容器——
              hero（空会话）时与标题同栈整体垂直居中；首条消息后 sticky 落底。 */}
          <div className="wb-ai-area" data-phase={restoring ? 'settling' : empty ? 'hero' : 'active'}>
            <div className="conv-flow" ref={flowRef} onScroll={handleFlowScroll}>
              {!empty && (
                <div className="msg-flow">
                  {displayMessages.map(m => (
                    m.role === 'user'
                      ? <UserMessage key={m.id} text={m.text} time={m.time} attachments={m.attachments} fileTags={m.fileTags} />
                      : <AssistantMessage
                        key={m.id}
                        {...m}
                        openPath={openPath}
                        canResumeTurn={!running}
                        onResumeTurn={handleResumeTurn}
                      />
                  ))}
                  {/* 对话流最后一行：整轮 Working 总指示（dsh TurnStatus，所有消息之后、输入框之前）。
                      startTime 锚定 turn/start 时间戳；冷恢复一个运行中的 turn 同样能正确计时。 */}
                  {running && <DeepDivingRow startTime={liveTurn?.startedAt ?? null} />}
                </div>
              )}

              <div className="conv-composer-seat">
                {/* 空态 hero：品牌标 + 价值主张 + 场景入口，与输入卡同栈居中 */}
                {empty && (
                  <>
                    <div className="conv-heroGlow" aria-hidden="true" />
                    <div className="conv-hero-head">
                      <HeroBrand />
                      <h2 className="conv-heroTitle">Aye</h2>
                      <p className="conv-heroSub">连接主机，用一句话完成巡检、排障与部署</p>
                      <div className="conv-heroChips">
                        {HERO_SCENES.map(({ label, icon: Icon }) => (
                          <button
                            key={label}
                            type="button"
                            className="conv-heroChip"
                            onClick={() => send({ text: label, attachments: [], fileTags: [] })}
                          >
                            <Icon className="conv-heroChipIcon" style={{ fontSize: 13 }} aria-hidden />
                            {label}
                          </button>
                        ))}
                      </div>
                    </div>
                  </>
                )}

                {/* composer 接管（dsh conversation.composer 链）：等待审批/提问期间
                    浮卡整体替换 HostConnectBar + InputBar，决议后输入框回来。 */}
                {activeInteraction === null ? (
                  <>
                    {/* 项目入口三态：
                        ① 部署模式中 → 模式条（火箭 · 项目名 · 退出）；
                        ② 已绑定项目 → 大胶囊消失，坍缩为输入框左下的迷你文件夹图标；
                           刚选中项目时额外给一条一次性「一键部署」建议（B 方案）；
                        ③ 未绑定项目 → 保留浅灰大胶囊引导选择。
                        行座与 HostConnectBar / 输入卡共用 736 居中列。 */}
                    {deployMode && workspace ? (
                      <div className="dmb-row">
                        <div className="dmb-bar">
                          <span className="dmb-icon" aria-hidden><Rocket size={13} /></span>
                          <span className="dmb-name">部署模式</span>
                          <span className="dmb-project" title={workspace.canonical_path}>· {workspace.title}</span>
                          <button
                            type="button"
                            className="dmb-exit"
                            aria-label="退出部署模式"
                            title="退出部署模式"
                            onClick={() => setDeployMode(false)}
                          >
                            <X size={12} strokeWidth={2.2} />
                          </button>
                        </div>
                      </div>
                    ) : !deployMode && workspace && deployHint ? (
                      <div className="dph-row">
                        <div className="dph-bar">
                          <button
                            type="button"
                            className="dph-go"
                            onClick={startQuickDeploy}
                            title={`按标准 7 阶段流水线部署 ${workspace.title}`}
                          >
                            <Rocket size={12} />
                            一键部署 {workspace.title} →
                          </button>
                          <button
                            type="button"
                            className="dph-close"
                            aria-label="关闭建议"
                            title="关闭（之后可用火箭钮或输入 /部署 进入部署模式）"
                            onClick={() => setDeployHint(false)}
                          >
                            <X size={12} strokeWidth={2.2} />
                          </button>
                        </div>
                      </div>
                    ) : !workspace && (
                      <div className="wp-triggerRow">
                        <button
                          ref={wsTriggerRef}
                          type="button"
                          className={`wp-trigger${pickerOpen ? ' is-open' : ''}`}
                          onClick={() => setPickerOpen(true)}
                          aria-haspopup="menu"
                          aria-expanded={pickerOpen}
                          title="选择项目（一键部署）"
                        >
                          <Folder size={16} strokeWidth={1.8} className="wp-triggerFolder" />
                          <span className="wp-triggerLabel">选择项目</span>
                          <ChevronDown size={12} strokeWidth={1.8} className={`wp-triggerChevron${pickerOpen ? ' is-open' : ''}`} />
                        </button>
                      </div>
                    )}

                    {/* 部署阶段卡：回放 deploy_stage 事件的固定 7 阶段，全会话唯一实例。 */}
                    <DeployDock key={`deploy-${sessionId ?? 'new'}`} stages={standingDeploy} />

                    {/* 待办码头：位于项目/部署入口下方、输入卡上方，全会话唯一实例；
                        全部完成后自动收口为可点开的极简摘要。key 绑会话：切会话折叠态重建。 */}
                    <TodoDock key={sessionId ?? 'new'} items={standingTodo} />

                    {/* 主机未连接/未配置预警条：发送前先引导，避免 AI 跑进 Think 才发现不可执行（C 方案）。 */}
                    <HostConnectBar />

                    {/* 底部：输入条，左下为部署/项目工具槽，右下执行模式切换器 + 模型座 */}
                    <InputBar
                      variant={empty ? 'hero' : 'composer'}
                      placeholder={empty ? '描述你想执行的运维任务…（输入 /部署 可进入一键部署模式）' : undefined}
                      value={draft}
                      onChange={setDraft}
                      onSubmit={handleSubmit}
                      onStop={stop}
                      running={running}
                      disabled={startingTurn}
                      tools={composerTools}
                      trailing={composerTrailing}
                    />
                  </>
                ) : activeInteraction.kind === 'confirm' ? (
                  <ComposerApprovalCard
                    key={activeInteraction.id}
                    data={activeInteraction.data}
                    onSettled={() => { /* API 已由卡片调用；接管解除以 interaction/resolved 事件为准 */ }}
                  />
                ) : (
                  <ComposerQuestionCard
                    key={activeInteraction.id}
                    data={activeInteraction.data}
                    onSettled={() => { /* 同上：作答 API 已由卡片调用 */ }}
                  />
                )}
              </div>
            </div>
          </div>

          {/* 右：终端/文件面板 */}
          {rightVisible && (
            <>
              <PanelResizer direction="vertical" onResize={handleResize} />
              <div className="wb-right-area" style={{ flex: `0 0 ${rightWidth}px` }}>
                <div className="wb-toolbar">
                  <div className="wb-toolbar-left">
                    <button
                      type="button"
                      className={`wb-tab-btn ${wbTab === 'terminal' ? 'active' : ''}`}
                      onClick={() => setWbTab('terminal')}
                    >
                      <SquareTerminal /> 终端
                    </button>
                    <button
                      type="button"
                      className={`wb-tab-btn ${wbTab === 'files' ? 'active' : ''}`}
                      onClick={() => setWbTab('files')}
                    >
                      <Folder /> 文件
                    </button>
                  </div>

                  <div className="wb-toolbar-center">
                    {wbTab === 'terminal' && (
                      <>
                        <Tooltip title="减小字号">
                          <button className="wb-font-btn" onClick={() => terminalPanelRef.current?.decreaseFontSize()}>
                            <span className="wb-font-a wb-font-a-sm">A</span>
                          </button>
                        </Tooltip>
                        <Tooltip title="增大字号">
                          <button className="wb-font-btn" onClick={() => terminalPanelRef.current?.increaseFontSize()}>
                            <span className="wb-font-a wb-font-a-lg">A</span>
                          </button>
                        </Tooltip>
                      </>
                    )}
                    {wbTab === 'files' && (
                      <>
                        <Tooltip title="返回根目录">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.goHome()}><Home /></button>
                        </Tooltip>
                        <Tooltip title="上级目录">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.goUp()}><ArrowUp /></button>
                        </Tooltip>
                        <Tooltip title="刷新">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.refresh()}><RotateCw /></button>
                        </Tooltip>
                        <Tooltip title="上传文件">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.upload()}><Upload /></button>
                        </Tooltip>
                        <Tooltip title="上传文件夹（保留目录结构）">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.uploadFolder()}>
                            <FolderUp />
                          </button>
                        </Tooltip>
                        <Tooltip title="选择本地工作区打包上传">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.archiveUpload()}>
                            <FileArchive />
                          </button>
                        </Tooltip>
                        <Tooltip title="新建文件夹">
                          <button className="wb-icon-btn" onClick={() => fileTreeRef.current?.mkdirHere()}>
                            <FolderPlus />
                          </button>
                        </Tooltip>
                      </>
                    )}
                  </div>

                  <div className="wb-toolbar-right">
                    <Tooltip title="隐藏终端/文件面板">
                      <button type="button" className="wb-icon-btn" onClick={toggleRightPanel}>
                        <FxNavIcon cls="pn-ico pn-close-r">
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                            <rect width="18" height="18" x="3" y="3" rx="2" />
                            <path className="pn-rail" d="M15 3v18" />
                            <path className="pn-chv" d="m8 9 3 3-3 3" />
                          </svg>
                        </FxNavIcon>
                      </button>
                    </Tooltip>
                    <Tooltip title="操作日志">
                      <button type="button" className="wb-icon-btn" onClick={() => navigate('/observability/logs')}>
                        <FileText />
                      </button>
                    </Tooltip>
                    <Tooltip title={currentStatus === 'connected' ? '断开连接' : currentStatus === 'error' ? '重新连接' : '连接服务器'}>
                      <button
                        type="button"
                        className={`wb-icon-btn wb-conn-btn ${currentStatus === 'connected' ? 'connected' : ''}`}
                        onClick={handleConnectAction}
                      >
                        <Power />
                      </button>
                    </Tooltip>
                  </div>
                </div>

                <div className="wb-tab-content">
                  <div className={`wb-tab-pane ${wbTab === 'terminal' ? 'active' : ''}`}>
                    <SshTerminalPanel ref={terminalPanelRef} hideHeader />
                  </div>
                  <div className={`wb-tab-pane ${wbTab === 'files' ? 'active' : ''}`}>
                    <ServerFileTree ref={fileTreeRef} hideHeader />
                  </div>
                </div>
                <UploadManager />
              </div>
            </>
          )}
        </div>
      </div>

      <WorkspacePicker
        open={pickerOpen}
        anchorRef={wsTriggerRef}
        sessionId={sessionId}
        selectedId={workspace?.id}
        onClose={() => setPickerOpen(false)}
        onSelect={(ws) => {
          setWorkspace(ws)
          // 仅用户在选择器内显式选中（或新建/拉取）时给一次性建议；
          // 会话绑定回填走 getBoundWorkspace effect，不经过这里，不会弹建议。
          setDeployHint(ws !== null)
        }}
      />
    </div>
  )
}
