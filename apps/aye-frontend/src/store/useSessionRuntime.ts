/**
 * 全局会话运行时：events 长连编排 + live turn 视图状态。
 *
 * 设计（spec D3）：
 * - 连接对象/计时器/refcount/folder 放在模块级非响应式 Map，不进 React；
 *   zustand 只承载渲染所需快照（每会话 LiveTurnView、连接状态、pending 列表）。
 * - attach/detach 引用计数：对话页/浮层挂载即 attach，卸载 detach；
 *   turn 在后台运行（phase=running）或存在待决议交互时，即使无人观看也保持连接；
 *   turn 结束且 refcount=0 后 30s 空闲销毁；活跃长连硬上限 4，超限淘汰最久未使用、
 *   非运行、无 pending 的会话。
 * - snapshot 首次全量经 initLiveFolder 重建；重连的增量 snapshot 与 live 事件统一
 *   走 folder.ingest（seq 去重），不镜像终端（终端只镜像挂载后新事件，见事件订阅）。
 */

import { create } from 'zustand'
import {
  createSessionEventsConnection,
  REASON,
  type ConnectionState,
  type SessionEvent,
  type SessionEventsConnection,
} from '@/api/sessionEventsApi'
import {
  initLiveFolder,
  LiveTurnFolder,
  type ActiveInteraction,
  type LiveTurnView,
} from '@/lib/liveTurnProjector'

/** turn 结束且无人观看后的连接保留时间。 */
const IDLE_DESTROY_MS = 30_000
/** 全局同时活跃的 events 长连硬上限。 */
const MAX_ACTIVE_CONNECTIONS = 4

export interface PendingInteractionEntry {
  sessionId: string
  interaction: ActiveInteraction
}

export type RuntimeEventListener = (ev: SessionEvent) => void

interface RuntimeStoreState {
  /** sessionId → 当前 live turn 视图（null=无未闭合轮）。 */
  views: Record<string, LiveTurnView | null>
  /** sessionId → events 连接状态（调试/弱提示）。 */
  conn: Record<string, ConnectionState>
  /** 所有会话的待决议交互（全局浮层用）。 */
  pending: PendingInteractionEntry[]
  /** phase=running 的会话集合（侧栏运行点用）。 */
  runSessions: string[]
}

const useRuntimeStore = create<RuntimeStoreState>(() => ({
  views: {},
  conn: {},
  pending: [],
  runSessions: [],
}))

interface Runtime {
  sessionId: string
  refCount: number
  /** 已并入 folder 的最大事件 seq；-1 表示尚未做过首次快照。 */
  lastSeq: number
  folder: LiveTurnFolder | null
  connection: SessionEventsConnection | null
  connState: ConnectionState
  booted: boolean
  /**
   * 冷启动多页补页期间的事件缓冲（booted=false 时非 null）；抵达日志尾
   * （最后一页 hasMore=false）后一次性 initLiveFolder，避免长会话首页中
   * 「开始在页内、结束在页外」的历史轮在补页中途被误投影为 running。
   */
  bootBuffer: SessionEvent[] | null
  listeners: Set<RuntimeEventListener>
  idleTimer: ReturnType<typeof setTimeout> | null
  lastTouchAt: number
  /**
   * live 事件发布合流：LLM 流式 delta 每秒可达数十上百条，逐条同步 setState 会让
   * React 每帧重渲染多次（每次都全量重解析 Markdown），主线程被打满导致页面
   * 点不动、滚不动。这里按动画帧合流——一帧内多条事件只做一次 publish。
   */
  publishQueued: boolean
  rafId: number | null
}

const runtimes = new Map<string, Runtime>()

// ---------------------------------------------------------------------------
// 内部工具
// ---------------------------------------------------------------------------

function clearIdle(rt: Runtime): void {
  if (rt.idleTimer) {
    clearTimeout(rt.idleTimer)
    rt.idleTimer = null
  }
}

/**
 * 按动画帧合流发布视图：一帧内的多条事件（典型场景：一条网络 chunk 内多个
 * token delta）折叠成一次 store 提交，把 React 渲染上限钉在 ~60fps，
 * 给点击/滚动等输入事件留出主线程时间。
 * 事件 ingest 与原始监听器分发仍逐条即时执行（终端镜像/后台保活判定不延迟）。
 */
function schedulePublish(rt: Runtime): void {
  if (rt.publishQueued) return
  rt.publishQueued = true
  const flush = (): void => {
    rt.publishQueued = false
    rt.rafId = null
    publish(rt)
  }
  if (typeof requestAnimationFrame === 'function') {
    rt.rafId = requestAnimationFrame(flush)
  } else {
    // 非 DOM 环境兜底（正常浏览器走 rAF 分支）。
    rt.rafId = setTimeout(flush, 16) as unknown as number
  }
}

/** 取消挂起的合流发布（直接 publish 前/运行时销毁时调用，避免发布已过期视图）。 */
function cancelScheduledPublish(rt: Runtime): void {
  if (rt.rafId !== null) {
    if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(rt.rafId)
    else clearTimeout(rt.rafId)
    rt.rafId = null
  }
  rt.publishQueued = false
}

function setConnState(rt: Runtime, state: ConnectionState): void {
  rt.connState = state
  useRuntimeStore.setState((s) => ({ conn: { ...s.conn, [rt.sessionId]: state } }))
}

/** 把 folder 快照提交到响应式 store；仅在身份变化时更新 pending/runSessions 引用。 */
function publish(rt: Runtime): void {
  const id = rt.sessionId
  const view = rt.folder ? rt.folder.snapshot() : null
  useRuntimeStore.setState((state) => {
    const prevView = state.views[id] ?? null
    const patch: Partial<RuntimeStoreState> = {
      views: { ...state.views, [id]: view },
    }

    const newAi = view?.activeInteraction ?? null
    const oldEntry = state.pending.find((e) => e.sessionId === id)
    const sameAi = !!oldEntry && !!newAi
      && oldEntry.interaction.interactionId === newAi.interactionId
    if (!sameAi) {
      patch.pending = newAi
        ? [...state.pending.filter((e) => e.sessionId !== id), { sessionId: id, interaction: newAi }]
        : state.pending.filter((e) => e.sessionId !== id)
    }

    const wasRunning = prevView?.phase === 'running'
    const isRunning = view?.phase === 'running'
    if (wasRunning !== isRunning) {
      patch.runSessions = isRunning
        ? [...state.runSessions.filter((x) => x !== id), id]
        : state.runSessions.filter((x) => x !== id)
      if (isRunning) {
        console.info(`[runtime] ${id} turn 开始（后台保持连接）`)
      } else {
        console.info(`[runtime] ${id} turn 结束（reason=${view?.reason ?? 'unknown'}）`)
      }
    }
    return patch
  })
}

/**
 * 无人观看时的保留决策：running / 待交互 → 保持；其余 → 30s 后销毁。
 * 在 detach 与每条事件后调用（幂等：先清旧 timer）。
 */
function evaluateBackground(rt: Runtime): void {
  const view = rt.folder ? rt.folder.snapshot() : null
  const keep = rt.refCount > 0
    || view?.phase === 'running'
    || !!view?.activeInteraction
  clearIdle(rt)
  if (!keep) {
    rt.idleTimer = setTimeout(() => {
      console.info(`[runtime] ${rt.sessionId} 空闲 ${IDLE_DESTROY_MS}ms，销毁运行时`)
      destroyRuntime(rt.sessionId)
    }, IDLE_DESTROY_MS)
  }
}

function handleEvent(rt: Runtime, ev: SessionEvent): void {
  if (typeof ev.seq === 'number' && ev.seq <= rt.lastSeq) return
  if (typeof ev.seq === 'number') rt.lastSeq = Math.max(rt.lastSeq, ev.seq)
  rt.lastTouchAt = Date.now()

  // 原始事件订阅者（终端联动只镜像挂载后的新事件；历史快照不走这里）
  rt.listeners.forEach((cb) => {
    try {
      cb(ev)
    } catch (err) {
      console.warn(`[runtime] ${rt.sessionId} 事件监听器异常`, err)
    }
  })

  if (!rt.folder) {
    // 空闲会话只有 turn/start 能开启 folder（其余迟到事件忽略，snapshot 已覆盖历史）
    if (ev.type !== 'turn/start') return
    rt.folder = new LiveTurnFolder(
      Number(ev.data?.turn ?? ev.turnNo) || 0,
      Number(ev.tsMs),
    )
  }
  if (!rt.folder.ingest(ev)) return
  schedulePublish(rt)
  evaluateBackground(rt)
}

function connect(rt: Runtime): void {
  clearIdle(rt)
  setConnState(rt, 'connecting')
  rt.connection = createSessionEventsConnection(rt.sessionId, {
    getAfter: () => rt.lastSeq,
    handlers: {
      onSnapshot: (events, hasMore) => {
        // 快照是权威全量/追赶基线：撤掉帧内挂起的增量发布，避免旧视图多渲一帧。
        cancelScheduledPublish(rt)
        let maxSeq = rt.lastSeq
        for (const ev of events) {
          if (typeof ev.seq === 'number') maxSeq = Math.max(maxSeq, ev.seq)
        }
        rt.lastSeq = maxSeq
        if (!rt.booted) {
          // 冷启动补页可能跨多页：先缓冲，到日志尾再全量重建，
          // 不在补页中途发布视图（历史轮的结束可能落在后续页）。
          if (!rt.bootBuffer) rt.bootBuffer = []
          rt.bootBuffer.push(...events)
          if (!hasMore) {
            rt.folder = initLiveFolder(rt.bootBuffer)
            rt.bootBuffer = null
            rt.booted = true
            publish(rt)
          }
        } else if (rt.folder) {
          // 重连增量：与 live 同路径 fold（seq 已去重）
          for (const ev of events) rt.folder.ingest(ev)
          publish(rt)
        } else {
          // 重连且本地无轮：尝试从增量快照建立
          rt.folder = initLiveFolder(events)
          publish(rt)
        }
        evaluateBackground(rt)
      },
      onEvent: (ev) => handleEvent(rt, ev),
      onStateChange: (state) => setConnState(rt, state),
    },
  })
}

/** 连接数超上限时淘汰最久未使用、可释放（无人观看/非运行/无交互）的会话。 */
function enforceCap(): void {
  const active = [...runtimes.values()].filter((rt) => !!rt.connection)
  if (active.length < MAX_ACTIVE_CONNECTIONS) return
  const victims = active
    .filter((rt) => {
      if (rt.refCount > 0 || rt.idleTimer) return false
      const view = rt.folder ? rt.folder.snapshot() : null
      return view?.phase !== 'running' && !view?.activeInteraction
    })
    .sort((a, b) => a.lastTouchAt - b.lastTouchAt)
  const need = active.length - MAX_ACTIVE_CONNECTIONS + 1
  for (const victim of victims.slice(0, need)) {
    console.info(`[runtime] 连接数达上限，淘汰空闲会话 ${victim.sessionId}`)
    destroyRuntime(victim.sessionId)
  }
}

function destroyRuntime(sessionId: string): void {
  const rt = runtimes.get(sessionId)
  if (!rt) return
  clearIdle(rt)
  // 销毁后 rAF 回调绝不能再触发：publish 会把已删除的视图重新写回 store。
  cancelScheduledPublish(rt)
  rt.connection?.close()
  rt.connection = null
  rt.listeners.clear()
  runtimes.delete(sessionId)
  useRuntimeStore.setState((s) => {
    if (!(sessionId in s.views) && !(sessionId in s.conn)
      && !s.pending.some((e) => e.sessionId === sessionId)
      && !s.runSessions.includes(sessionId)) {
      return s
    }
    const views = { ...s.views }
    delete views[sessionId]
    const conn = { ...s.conn }
    delete conn[sessionId]
    return {
      views,
      conn,
      pending: s.pending.filter((e) => e.sessionId !== sessionId),
      runSessions: s.runSessions.filter((x) => x !== sessionId),
    }
  })
}

// ---------------------------------------------------------------------------
// 对外命令式 API
// ---------------------------------------------------------------------------

/** 订阅某会话的 events（对话页挂载后调用；同一会话多组件共享一条连接）。 */
export function attachSessionRuntime(sessionId: string): void {
  if (!sessionId) return
  const existing = runtimes.get(sessionId)
  if (existing) {
    existing.refCount += 1
    existing.lastTouchAt = Date.now()
    clearIdle(existing)
    // 复用保活 runtime 时与冷启动口径对齐：已落库的闭合轮（completed/error/aborted 等，
    // 旧 messages 表已有 recorder 投影）交给本次重拉的服务端基线，丢弃残留 ended folder，
    // 否则同一轮既在基线又在 live → 重复渲染、错轮 endNote 也会错显；interrupted 冷恢复
    // 轮例外（旧表无该轮投影，必须继续投影其现场，与 initLiveFolder 的例外一致）。
    if (existing.folder
      && existing.folder.phase === 'ended'
      && existing.folder.reason !== REASON.INTERRUPTED) {
      cancelScheduledPublish(existing)
      existing.folder = null
      publish(existing)
    }
    if (!existing.connection) {
      console.info(`[runtime] ${sessionId} 重新接入（refCount=${existing.refCount}）`)
      connect(existing)
    }
    return
  }
  enforceCap()
  const rt: Runtime = {
    sessionId,
    refCount: 1,
    lastSeq: -1,
    folder: null,
    connection: null,
    connState: 'connecting',
    booted: false,
    bootBuffer: null,
    listeners: new Set(),
    idleTimer: null,
    lastTouchAt: Date.now(),
    publishQueued: false,
    rafId: null,
  }
  runtimes.set(sessionId, rt)
  console.info(`[runtime] ${sessionId} attach（refCount=1），建立 events 连接`)
  connect(rt)
}

/** 取消订阅；refcount 归零后按后台策略保留或延迟销毁。 */
export function detachSessionRuntime(sessionId: string): void {
  const rt = runtimes.get(sessionId)
  if (!rt) return
  rt.refCount = Math.max(0, rt.refCount - 1)
  rt.lastTouchAt = Date.now()
  if (rt.refCount === 0) {
    console.info(`[runtime] ${sessionId} detach（refCount=0），按后台策略评估`)
    evaluateBackground(rt)
  }
}

/** 立即销毁某会话运行时（删除会话时调用）。 */
export function destroySessionRuntime(sessionId: string): void {
  console.info(`[runtime] ${sessionId} 主动销毁`)
  destroyRuntime(sessionId)
}

/**
 * 订阅挂载后的原始 live 事件（终端/文件树联动用；snapshot 历史事件不投递）。
 * 必须在 attach 之后注册；返回取消函数。
 */
export function addRuntimeEventListener(
  sessionId: string,
  cb: RuntimeEventListener,
): () => void {
  const rt = runtimes.get(sessionId)
  if (!rt) return () => {}
  rt.listeners.add(cb)
  return () => {
    rt.listeners.delete(cb)
  }
}

/** 命令式读取某会话当前 live 视图（非响应式；事件回调/挂载补刷用）。 */
export function peekLiveView(sessionId: string): LiveTurnView | null {
  const rt = runtimes.get(sessionId)
  return rt?.folder ? rt.folder.snapshot() : null
}

// ---------------------------------------------------------------------------
// React 选择器
// ---------------------------------------------------------------------------

/** 当前会话的 live turn 视图（无未闭合轮时为 null）。 */
export function useLiveTurn(sessionId?: string | null): LiveTurnView | null {
  return useRuntimeStore((s) => (sessionId ? (s.views[sessionId] ?? null) : null))
}

/** 当前会话是否有 turn 正在运行（agentRunning 的单一真值来源）。 */
export function useRuntimeRunning(sessionId?: string | null): boolean {
  return useRuntimeStore((s) => {
    if (!sessionId) return false
    return s.views[sessionId]?.phase === 'running'
  })
}

export function useRuntimeConnState(sessionId?: string | null): ConnectionState | null {
  return useRuntimeStore((s) => (sessionId ? (s.conn[sessionId] ?? null) : null))
}

/** 所有会话的待决议交互（全局浮层；pending 数组引用仅在身份变化时更新）。 */
export function usePendingInteractions(): PendingInteractionEntry[] {
  return useRuntimeStore((s) => s.pending)
}

/** phase=running 的会话 id 集合（侧栏运行指示）。 */
export function useRunningSessionIds(): string[] {
  return useRuntimeStore((s) => s.runSessions)
}

/**
 * 全部会话的 live 视图映射（全局后台结束通知做边沿监听用）。
 * 注意：每次 publish 该对象引用都会更新，仅适合应用层单例订阅，勿用于高频渲染。
 */
export function useRuntimeViews(): Record<string, LiveTurnView | null> {
  return useRuntimeStore((s) => s.views)
}

// dev-only：本模块持有模块级命令式状态（runtimes 内的 events fetch 长连）。
// HMR 热替换时旧 Map 被整体丢弃，旧长连无人 close 会成为孤儿流，持续占用浏览器
// 同域连接预算（HTTP/1.1 每源 6 条），累积后所有请求挂死。dispose 先关连接，
// decline 让 Vite 整页重载——带全局命令式状态的模块不适合组件级热替换。
const hmr = (import.meta as unknown as {
  hot?: { dispose(cb: () => void): void; decline(): void }
}).hot
if (hmr) {
  hmr.dispose(() => {
    runtimes.forEach((rt) => {
      try {
        rt.connection?.close()
      } catch {
        /* dev-only 兜底：忽略关闭异常 */
      }
    })
  })
  hmr.decline()
}
