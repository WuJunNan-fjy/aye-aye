/**
 * 会话事件日志 SSE 客户端（GET /api/sessions/{id}/events）。
 *
 * 信封（event: message + data JSON）：
 *   hello    { type:'hello', sessionId, seq }
 *   snapshot { type:'snapshot', hasMore, events:[SessionEvent] }
 *   event    { type:'event', event: SessionEvent }
 *   ping     { type:'ping' }                —— 保活，无业务含义
 *   error    { type:'error', message }      —— 非致命，记录后继续/重连
 *
 * 连接管理器内置自动重连：500ms 起指数退避、上限 10s、±25% 抖动；
 * 每次连接带 after=已处理最大 seq；snapshot 与 live 的重叠由上层按 seq 去重。
 * 不引第三方 SSE 库，复用 agentApi.readSSE 的帧解析。
 */

import { API_BASE, authHeaders } from '@/api/request'
import { readSSE } from '@/api/agentApi'

// ---- 事件词汇表（与后端 SessionEventType 字符串一一对应） ----

export const ET = {
  TURN_START: 'turn/start',
  TURN_END: 'turn/end',
  STEP_START: 'step/start',
  STEP_END: 'step/end',
  USER_MESSAGE: 'user/message',
  ASSISTANT_CHUNK: 'assistant/chunk',
  ASSISTANT_MESSAGE: 'assistant/message',
  TOOL_CALL: 'tool/call',
  TOOL_DECIDED: 'tool/decided',
  TOOL_RESULT: 'tool/result',
  INTERACTION_REQUESTED: 'interaction/requested',
  INTERACTION_RESOLVED: 'interaction/resolved',
  REQUEST_HEADER: 'request/header',
  COMPACTION_SUMMARY: 'compaction/summary',
  TERMINAL_MIRROR: 'terminal/mirror',
  CONTEXT_INJECTED: 'context/injected',
  ERROR: 'error',
  SESSION_END_SEED: 'session/end-seed',
} as const

/** turn/end reason 常量。 */
export const REASON = {
  COMPLETED: 'completed',
  ERROR: 'error',
  ABORTED: 'aborted',
  BLOCKED: 'blocked',
  MAX_TOKENS: 'max_tokens',
  INTERRUPTED: 'interrupted',
} as const

/** user/message source。 */
export const SOURCE_USER = 'user'
export const SOURCE_INJECT = 'inject'

/** interaction kind。 */
export const KIND_CONFIRM = 'confirm'
export const KIND_ASK = 'ask'

/** 一条会话事件（后端 SessionEvent，camelCase）。 */
export interface SessionEvent {
  type: string
  seq: number
  tsMs: number
  turnNo: number
  stepNo: number
  ignorable: boolean
  surfaceOp?: string | null
  sourceSeqs?: number[] | null
  data: Record<string, any>
}

export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface EventsHandlers {
  /** hello：服务端当前日志长度（下一条事件 seq）。 */
  onHello?: (serverSeq: number) => void
  /** 快照事件（seq > after 的历史，升序）；与后续 live 可能重叠，按 seq 去重。 */
  onSnapshot?: (events: SessionEvent[], hasMore: boolean) => void
  /** 一条 live 事件。 */
  onEvent?: (ev: SessionEvent) => void
  onStateChange?: (state: ConnectionState, attempt: number) => void
  /** 服务端 error 信封（非致命）；网络错误也走这里做诊断。 */
  onProtocolError?: (message: string) => void
}

export interface EventsConnectionOptions {
  /** 发起（每次重连）前取最新已处理 seq；返回 -1 表示从头。 */
  getAfter: () => number
  handlers: EventsHandlers
}

export interface SessionEventsConnection {
  close(): void
}

const BASE_DELAY_MS = 500
const MAX_DELAY_MS = 10_000

/**
 * 读停滞看门狗阈值。服务端每 25s 发一次 ping；若超过该时长没有收到任何帧
 * （含 ping），说明连接已半开（服务端监听者已摘除并取消 ping、NAT 静默丢弃、
 * 代理僵死等——此时 fetch reader 既无数据也无错误，不会自行触发重连），
 * 主动 abort 当前连接并按 after=lastSeq 重连。
 */
const READ_STALL_MS = 35_000

/** 历史补页单页上限（与后端 snapshot cap 对齐）与防御性最大页数。 */
const SNAPSHOT_PAGE_GUARD = 100

/** 指数退避 + ±25% 抖动。 */
function backoffDelay(attempt: number): number {
  const base = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** attempt)
  return Math.round(base * (0.75 + Math.random() * 0.5))
}

/**
 * 抓取一页 snapshot 历史（升序）。events 端点恒为 SSE 信封，snapshot 是 hello 后
 * 第一个信封；拿到即主动中断本次短连，不保持为 live 长连。
 */
async function fetchSnapshotPage(
  sessionId: string,
  after: number,
  signal: AbortSignal,
): Promise<{ events: SessionEvent[]; hasMore: boolean }> {
  // 每页独立 AbortController：读完 snapshot 后立即 abort 彻底释放 socket。
  // 仅 res.body.cancel() 在服务端仍保持 SSE 长连时不会马上关闭底层连接，
  // 连续补页会耗尽浏览器单源 6 连接配额，后续页静默排队挂起。
  const pageAbort = new AbortController()
  const onParentAbort = () => pageAbort.abort()
  if (signal.aborted) pageAbort.abort()
  else signal.addEventListener('abort', onParentAbort, { once: true })
  const res = await fetch(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events?after=${after}`,
    { headers: await authHeaders({ Accept: 'text/event-stream' }), signal: pageAbort.signal },
  )
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
  try {
    for await (const env of readSSE(res)) {
      if (pageAbort.signal.aborted) break
      if (env?.type === 'snapshot') {
        return {
          events: Array.isArray(env.events) ? (env.events as SessionEvent[]) : [],
          hasMore: env.hasMore === true,
        }
      }
    }
    // 流在 snapshot 前结束：按无增量处理，交由上层的主长连/重连兜底
    return { events: [], hasMore: false }
  } finally {
    res.body.cancel().catch(() => { /* 连接已结束 */ })
    pageAbort.abort() // 立即 FIN/RST，归还连接池配额
    signal.removeEventListener('abort', onParentAbort)
  }
}

/**
 * 建立带自动重连的 events 长连。close() 后所有回调不再触发、定时器全部清理。
 */
export function createSessionEventsConnection(
  sessionId: string,
  options: EventsConnectionOptions,
): SessionEventsConnection {
  const { getAfter, handlers } = options
  let closed = false
  let attempt = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let currentAbort: AbortController | null = null
  /** 当前连接尝试的读停滞看门狗（每次重连重建，close 时清理）。 */
  let stallTimer: ReturnType<typeof setTimeout> | null = null
  let disposed = false

  const setState = (state: ConnectionState) => {
    try {
      handlers.onStateChange?.(state, attempt)
    } catch { /* 回调异常不影响连接 */ }
  }

  const scheduleReconnect = (reason: string) => {
    if (closed) return
    const delay = backoffDelay(attempt)
    attempt += 1
    console.info(`[events] ${sessionId} ${reason}，${delay}ms 后第 ${attempt} 次重连`)
    setState('reconnecting')
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void connect()
    }, delay)
  }

  const connect = async (): Promise<void> => {
    if (closed) return
    const startAfter = getAfter()
    currentAbort = new AbortController()
    // 读停滞看门狗：任何帧（hello/snapshot/event/ping）到达都续命；
    // 超时则 abort 本次 fetch，reader 抛错后统一走 scheduleReconnect。
    const armStallWatch = () => {
      if (stallTimer) clearTimeout(stallTimer)
      stallTimer = setTimeout(() => {
        if (closed) return
        console.warn(`[events] ${sessionId} ${READ_STALL_MS}ms 无任何帧，判定连接半开，主动重连`)
        currentAbort?.abort()
      }, READ_STALL_MS)
    }
    armStallWatch()
    setState(attempt === 0 ? 'connecting' : 'reconnecting')
    console.debug(`[events] ${sessionId} open after=${startAfter} attempt=${attempt}`)

    // 1) 历史追赶：snapshot 单页上限 500，hasMore 时按本页末条 seq 续抓，
    //    直到抵达日志尾部。否则长会话冷恢复时最新轮的 turn/end 落在截断窗口
    //    之外，上层 live 视图会永久卡在 running（AC-2）。
    try {
      let after = startAfter
      for (let page = 0; page < SNAPSHOT_PAGE_GUARD; page++) {
        if (closed) return
        const snap = await fetchSnapshotPage(sessionId, after, currentAbort.signal)
        if (snap.events.length > 0) {
          armStallWatch() // 补页本身也是存活证据
          handlers.onSnapshot?.(snap.events, snap.hasMore)
          after = snap.events[snap.events.length - 1].seq
        }
        if (!snap.hasMore || snap.events.length === 0) break
      }
    } catch (err) {
      if (closed) return
      if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
      const msg = err instanceof Error ? err.message : String(err)
      console.warn(`[events] ${sessionId} 历史补页失败：${msg}`)
      scheduleReconnect('历史补页失败')
      return
    }
    if (closed) return

    // 2) 主 live 长连：after 已推进到日志尾部，snapshot 仅含追赶窗口之后的增量，
    //    与补页重叠的部分由上层按 seq 去重。
    let res: Response
    try {
      res = await fetch(
        `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/events?after=${getAfter()}`,
        { headers: await authHeaders({ Accept: 'text/event-stream' }), signal: currentAbort.signal },
      )
    } catch (err) {
      if (closed) return
      if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
      const msg = err instanceof Error ? err.message : String(err)
      console.warn(`[events] ${sessionId} 连接失败：${msg}`)
      scheduleReconnect('连接失败')
      return
    }

    if (!res.ok || !res.body) {
      if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
      if (!closed) scheduleReconnect(`HTTP ${res.status}`)
      return
    }

    // hello 之后连接才算真正建立；重置退避。
    let greeted = false
    // 服务端 live 分发线程与 snapshot 发送线程相互独立（synchronized 只保证不交错、
    // 不保证先后），极端调度下 live 帧可能先于本连接的 snapshot 帧到达。
    // snapshot 落袋前先缓存这些 live 帧，snapshot 交给上层后再按 seq 升序补发：
    // 否则 folder 先收高 seq 事件，snapshot 内的低 seq 事件会被水位去重永久丢弃。
    let snapshotSeen = false
    const earlyLive: SessionEvent[] = []
    const flushEarlyLive = (): void => {
      if (earlyLive.length > 1) earlyLive.sort((a, b) => a.seq - b.seq)
      for (const ev of earlyLive) handlers.onEvent?.(ev)
      earlyLive.length = 0
    }
    try {
      for await (const env of readSSE(res)) {
        if (closed) return
        armStallWatch() // 收到任意帧（含 ping）即续命
        const kind = env?.type
        if (kind === 'hello') {
          greeted = true
          attempt = 0
          setState('open')
          handlers.onHello?.(Number(env.seq ?? 0))
        } else if (kind === 'snapshot') {
          const events = Array.isArray(env.events) ? (env.events as SessionEvent[]) : []
          handlers.onSnapshot?.(events, env.hasMore === true)
          if (!snapshotSeen) {
            snapshotSeen = true
            flushEarlyLive()
          }
        } else if (kind === 'event') {
          if (env.event && typeof env.event === 'object') {
            const ev = env.event as SessionEvent
            if (!snapshotSeen) earlyLive.push(ev)
            else handlers.onEvent?.(ev)
          }
        } else if (kind === 'ping') {
          // 保活：无业务处理
        } else if (kind === 'error') {
          const message = typeof env.message === 'string' ? env.message : '服务端事件错误'
          console.warn(`[events] ${sessionId} error 信封：${message}`)
          handlers.onProtocolError?.(message)
        }
      }
    } catch (err) {
      if (closed) return
      const msg = err instanceof Error ? err.message : String(err)
      console.warn(`[events] ${sessionId} 流中断：${msg}`)
    }

    // 流在 snapshot 前就结束（极端错误/竞态）：缓存帧不能吞，按序补发；
    // 与随后重连的 snapshot 重叠部分由上层按 seq 去重。
    if (!snapshotSeen) flushEarlyLive()

    if (closed) return
    if (stallTimer) { clearTimeout(stallTimer); stallTimer = null }
    // 流正常结束（服务端 complete）也重连——events 是长连契约。
    scheduleReconnect(greeted ? '连接结束' : '连接在 hello 前结束')
  }

  void connect()

  return {
    close() {
      if (disposed) return
      disposed = true
      closed = true
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      if (stallTimer) {
        clearTimeout(stallTimer)
        stallTimer = null
      }
      currentAbort?.abort()
      currentAbort = null
      console.debug(`[events] ${sessionId} closed by client`)
    },
  }
}
