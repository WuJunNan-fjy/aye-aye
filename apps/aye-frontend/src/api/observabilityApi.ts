/**
 * 观测中心数据层：操作日志（activity）与 Agent 追踪（turns / trajectory）。
 *
 * 全部读自后端事件日志投影（/api/observability/*），事实源是 session_events /
 * session_turns，与对话页共用同一份事件流——页面刷新、后端重启后历史一致，
 * 且每条记录带 sessionId / turnNo / seq，可与对话双向跳转。
 */
import request from '@/api/request'

// ---------------- 操作日志（工具操作行） ----------------

export type ActivityStatus = 'running' | 'success' | 'failed' | 'interrupted'
export type ActivityApproval = 'auto' | 'approved' | 'rejected' | 'pending'

export interface ActivityRow {
  callId: string
  sessionId: string
  sessionTitle?: string | null
  seq: number
  turnNo: number
  stepNo: number
  toolName: string
  command: string
  arguments?: unknown
  hostKey?: string | null
  hostId?: number | null
  hostName?: string | null
  status: ActivityStatus
  approval: ActivityApproval
  /** 已完成工具的实测耗时（ms） */
  durationMs?: number | null
  /** 运行中工具的实时已耗时（ms，查询时刻快照） */
  elapsedMs?: number
  output?: string | null
  exitCode?: number | null
  interrupted: boolean
  tsMs: number
  mode?: string | null
}

export interface ActivityStats {
  total: number
  success: number
  failed: number
  interrupted: number
  running: number
  today: number
  byStatus?: Record<string, number>
}

export interface ActivityQuery {
  limit?: number
  offset?: number
  hostId?: number
  sessionId?: string
  toolName?: string
  status?: string
  approvalStatus?: string
  /** epoch ms，含 */
  startMs?: number
  endMs?: number
  search?: string
}

export interface ActivityResult {
  rows: ActivityRow[]
  total: number
  stats: ActivityStats
  tools: string[]
}

// ---------------- Agent 追踪（turn / trajectory） ----------------

export type TurnState =
  | 'running'
  | 'completed'
  | 'error'
  | 'aborted'
  | 'blocked'
  | 'max_tokens'
  | 'interrupted'

export interface TurnRow {
  sessionId: string
  sessionTitle?: string | null
  hostId?: number | null
  hostName?: string | null
  turnNo: number
  state: TurnState
  reason?: string | null
  modelId?: string | null
  mode?: string | null
  startedMs: number
  endedMs?: number | null
  durationMs?: number | null
  steps: number
  toolCalls: number
  interactions: number
  errors: number
  tokensIn: number
  tokensOut: number
  tokensTotal: number
}

export interface TurnQuery {
  limit?: number
  offset?: number
  hostId?: number
  state?: string
  mode?: string
  search?: string
}

export interface TurnResult {
  turns: TurnRow[]
  total: number
}

/** 会话级聚合行：一个对话一条，turn 指标在后端汇总 */
export interface SessionRow {
  sessionId: string
  sessionTitle?: string | null
  hostId?: number | null
  hostName?: string | null
  turnCount: number
  /** 最近一轮的状态，代表对话当前所处状态 */
  state: TurnState
  reason?: string | null
  /** 最近一轮使用的模型/模式 */
  modelId?: string | null
  mode?: string | null
  /** 首轮开始时间 */
  startedMs: number
  /** 最近一轮开始时间 */
  lastMs: number
  /** 各轮耗时之和 */
  durationMs: number
  steps: number
  toolCalls: number
  interactions: number
  errors: number
  errorTurns: number
  tokensIn: number
  tokensOut: number
  tokensTotal: number
}

export interface SessionResult {
  sessions: SessionRow[]
  total: number
}

export interface TrajectoryTurn {
  turnNo: number
  status: string
  reason?: string | null
  modelId?: string | null
  mode?: string | null
  startedMs: number
  endedMs?: number | null
  tokensIn: number
  tokensOut: number
  tokensTotal: number
}

/** trajectory 时间线行（判别联合，kind 决定可用字段） */
export type TrajectoryRow =
  | ({ kind: 'turn-start' } & TraceCoord & { modelId?: string | null; mode?: string | null })
  | ({ kind: 'turn-end' } & TraceCoord & { reason: string; errorMessage?: string | null })
  | ({ kind: 'user' } & TraceCoord & { text: string; source: 'user' | 'inject' })
  | ({ kind: 'request' } & TraceCoord & { model?: string | null; mode?: string | null; toolsCount: number })
  | ({ kind: 'step-start' } & TraceCoord)
  | ({ kind: 'step-end' } & TraceCoord & { durationMs?: number | null })
  | ({ kind: 'assistant' } & TraceCoord & AssistantDetail)
  | ({ kind: 'tool' } & ToolDetail)
  | ({ kind: 'interaction' } & InteractionDetail)
  | ({ kind: 'compaction' } & TraceCoord & {
      summary: string
      replacedFromSeq: number
      replacedToSeq: number
    })
  | ({ kind: 'error' } & TraceCoord & { message: string; phase?: string | null })

export interface TraceCoord {
  seq: number
  turnNo: number
  stepNo: number
  tsMs: number
}

export interface AssistantDetail {
  text: string
  reasoning: string
  toolCalls: { callId?: string | null; name: string }[]
  model?: string | null
  usage?: { input: number; output: number; total: number }
  stepStartMs?: number | null
  firstChunkMs?: number | null
  ttftMs?: number | null
  messageTsMs: number
}

export interface ToolDetail extends TraceCoord {
  callId: string
  name: string
  arguments?: unknown
  hostKey?: string | null
  hostName?: string | null
  command: string
  approval: 'auto' | 'approved' | 'rejected' | 'pending'
  decidedBy?: string | null
  isError?: boolean
  interrupted?: boolean
  status: 'running' | 'success' | 'failed' | 'interrupted'
  output?: string | null
  exitCode?: number | null
  durationMs?: number | null
  callTsMs: number
  resultTsMs?: number
  confirmMessage?: string | null
  confirmTags?: string[]
}

export interface InteractionDetail extends TraceCoord {
  interactionKind: 'ask'
  interactionId: string
  question: string
  hint?: string | null
  options?: string[]
  resolved: boolean
  skipped?: boolean
  interrupted?: boolean
  answer?: string | null
}

export interface TrajectoryResult {
  session: { id: string; title?: string | null }
  turns: TrajectoryTurn[]
  rows: TrajectoryRow[]
}

// ---------------- 请求 ----------------

export default {
  activity: (params: ActivityQuery = {}) =>
    request.get('/observability/activity', { params }) as Promise<ActivityResult>,

  turns: (params: TurnQuery = {}) =>
    request.get('/observability/turns', { params }) as Promise<TurnResult>,

  sessions: (params: TurnQuery = {}) =>
    request.get('/observability/sessions', { params }) as Promise<SessionResult>,

  trajectory: (sessionId: string) =>
    request.get(
      `/observability/sessions/${encodeURIComponent(sessionId)}/trajectory`,
    ) as Promise<TrajectoryResult>,
}
