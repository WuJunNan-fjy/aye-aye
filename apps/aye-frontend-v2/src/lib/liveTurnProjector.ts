/**
 * 未闭合 turn 事件投影器（纯函数）。
 *
 * 输入：GET events 的 snapshot / live SessionEvent 序列（已按 seq 升序）。
 * 输出：最后一个「未闭合 turn」的可渲染视图（结构贴合 ChatMessage，ConversationPage
 * 直接拼在历史基线之后渲染）；turn 正常闭合（completed 等）后基线（旧 messages 表）
 * 才是权威，投影返回 null。
 *
 * 刻意不做的事：
 * - 不投影 user/message（含 source=inject）：用户行由「历史基线 + 发送乐观行」承担，
 *   runAgent 在 turn/start 后立即把用户行写入旧 messages 表，投影会与之重复；
 * - 不 fold 全量历史：历史基线由 convertSessionMessages 承担；
 * - 无副作用、不依赖 React/zustand。
 *
 * 冷恢复例外：最后一个 turn 以 reason=interrupted 闭合（后端进程崩溃后
 * balanceInterrupted 合成）时，旧 messages 表没有该轮 recorder.finish() 的投影，
 * 故即使 turn 已闭合也仍投影该轮（phase=ended），让中断现场可见。
 */

import {
  ET, REASON,
  KIND_CONFIRM, KIND_ASK,
  type SessionEvent,
} from '@/api/sessionEventsApi'
import type {
  AssistantBlock, ChatMessage,
} from '@/components/chat/MessageItem'
import type { ToolCallRecord, ToolStatus } from '@/components/chat/ToolCard'
import type { ConfirmationCardData, AskCardData } from '@/components/chat/InteractionCards'
import { isMutationTool } from '@/lib/remoteTools'

/** 工具记录：在 ToolCallRecord 基础上保留联动/中断所需字段。 */
export interface LiveToolCallRecord extends ToolCallRecord {
  /** 复合键 `${turnNo}:${callId}`（callId 序号跨 turn 复用）。 */
  key: string
  callId: string
  hostKey?: string
  exitCode?: number | null
  interrupted?: boolean
  decision?: 'allow' | 'deny'
  decidedBy?: 'user' | 'auto'
}

/** 一条 assistant 步消息（ChatMessage 同构，可直接渲染）。 */
export interface LiveStepMessage extends ChatMessage {
  role: 'assistant'
  stepNo: number
  /** 该步是否尚未收到权威 assistant/message（仍是 chunk 草稿）。 */
  streaming: boolean
}

export interface ActiveInteraction {
  kind: 'confirm' | 'ask'
  interactionId: string
  /** confirm 关联的工具调用复合键（用于定位内联卡）。 */
  callKey?: string
  data: ConfirmationCardData | AskCardData
}

export type EndNoteTone = 'stopped' | 'interrupted' | 'error'

export interface LiveTurnView {
  turnNo: number
  phase: 'running' | 'ended'
  reason?: string
  errorMessage?: string
  /** turn/start 的 tsMs（Working 耗时基准）。 */
  startedAt: number
  endedAt?: number
  /** 已并入视图的最大事件 seq（去重水位）。 */
  appliedSeq: number
  /** 按 step 升序的 assistant 步消息。 */
  stepMessages: LiveStepMessage[]
  /** 复合键 → 工具记录。 */
  toolsByKey: Record<string, LiveToolCallRecord>
  /** 待决议交互（confirm/ask）；同一时刻最多一个。 */
  activeInteraction: ActiveInteraction | null
  /** 非 completed 闭合的提示语（停止/中断/出错）。 */
  endNote?: { tone: EndNoteTone; text: string }
  /** 最近一次变更类工具结果时间（对话页重新挂载时补刷文件树用）。 */
  lastMutationAt?: number
  /** 交互是否曾被超时中断（诊断/浮层用）。 */
  hadInterruptedInteraction?: boolean
}

const STOPPED_HINT = '（已停止生成）'
const INTERRUPTED_HINT = '连接中断，本轮任务未完成（未完成的操作已标记中断）'

// ---------------------------------------------------------------------------
// 可变折叠草稿（每事件 O(1)~O(工具数) 增量维护；snapshot 浅重建为不可变视图）
// ---------------------------------------------------------------------------

interface StepDraft {
  stepNo: number
  thinking: string
  text: string
  /** 权威定稿前的 reasoning/text 草稿块是否存在。 */
  hasThinkingDraft: boolean
  hasTextDraft: boolean
  /** 定稿后的权威块（null=仍在流式草稿）。 */
  finalized: boolean
  /** 块序列：context / thinking / text / tool / interaction（去重）。 */
  blocks: AssistantBlock[]
  /** tool block 复合键有序集合（块去重 + tools 输出顺序）。 */
  toolKeys: string[]
  interactionIds: string[]
  /**
   * 快照版本：任何影响该步渲染的变化（delta/定稿/工具块/工具结果/交互决议）都 +1。
   * snapshot 据此跨帧复用视图对象——已封定步在流式期间零重渲染，
   * 否则每帧重建的 blocks/tools 新数组会击穿 AssistantMessage 的 memo。
   */
  version: number
}

/**
 * 可长期持有的增量折叠器（runtime 层每个会话一个）。
 * ingest 逐条增量维护（O(1)~O(工具数)）；snapshot() 浅重建不可变视图供 React 使用。
 * 收到下一轮 turn/start 时自动重置为新一轮。
 */
export class LiveTurnFolder {
  turnNo: number
  phase: 'running' | 'ended' = 'running'
  reason?: string
  errorMessage?: string
  startedAt: number
  endedAt?: number
  appliedSeq = -1
  endNote?: LiveTurnView['endNote']
  lastMutationAt?: number
  hadInterruptedInteraction = false

  private readonly steps = new Map<number, StepDraft>()
  private readonly stepOrder: number[] = []
  private readonly tools = new Map<string, LiveToolCallRecord>()
  private contextBlocks: AssistantBlock[] = []
  private contextAttached = false
  active: ActiveInteraction | null = null
  /** stepNo → 上一次构建的步视图；version 与 running 均未变时跨帧复用，保证 memo 有效。 */
  private stepViewCache = new Map<number, { version: number; running: boolean; view: LiveStepMessage }>()

  constructor(turnNo: number, startedAt: number) {
    this.turnNo = turnNo
    this.startedAt = startedAt
  }

  /** 重置为新一轮（下一轮 turn/start 到达时调用）。 */
  private reset(turnNo: number, startedAt: number): void {
    this.turnNo = turnNo
    this.startedAt = startedAt
    this.phase = 'running'
    this.reason = undefined
    this.errorMessage = undefined
    this.endedAt = undefined
    this.appliedSeq = -1
    this.endNote = undefined
    this.lastMutationAt = undefined
    this.hadInterruptedInteraction = false
    this.active = null
    this.steps.clear()
    this.stepOrder.length = 0
    this.tools.clear()
    this.contextBlocks = []
    this.contextAttached = false
    this.stepViewCache.clear()
  }

  private ensureStep(stepNo: number): StepDraft {
    const key = stepNo >= 0 ? stepNo : this.stepOrder.length + 1
    let s = this.steps.get(key)
    if (!s) {
      s = {
        stepNo: key,
        thinking: '',
        text: '',
        hasThinkingDraft: false,
        hasTextDraft: false,
        finalized: false,
        blocks: [],
        toolKeys: [],
        interactionIds: [],
        version: 0,
      }
      this.steps.set(key, s)
      this.stepOrder.push(key)
      // 第一条步消息承载上下文注入行（与 recorder 基线同构：context 在首条 assistant 段头部）
      if (!this.contextAttached && this.contextBlocks.length > 0) {
        s.blocks.unshift(...this.contextBlocks)
        this.contextAttached = true
      }
    }
    return s
  }

  private toolKey(callId: string): string {
    return `${this.turnNo}:${callId}`
  }

  private ensureTool(callId: string, name?: string, input?: unknown): LiveToolCallRecord {
    const key = this.toolKey(callId)
    let rec = this.tools.get(key)
    if (!rec) {
      rec = {
        key,
        id: key,
        callId,
        toolName: name ?? '',
        input,
        status: 'running',
      }
      this.tools.set(key, rec)
    }
    return rec
  }

  private appendToolBlock(s: StepDraft, key: string): void {
    if (!s.toolKeys.includes(key)) {
      s.toolKeys.push(key)
      s.blocks.push({ kind: 'tool', id: key })
    }
  }

  /**
   * 折叠一条事件。
   * @returns true 表示事件属于当前轮（或开启了新一轮）已接受；false 表示重复/异轮被忽略。
   */
  ingest(ev: SessionEvent): boolean {
    if (typeof ev.seq === 'number' && ev.seq <= this.appliedSeq) return false
    const evTurn = Number(ev.data?.turn ?? ev.turnNo) || 0
    if (ev.type === ET.TURN_START && evTurn !== this.turnNo) {
      this.reset(evTurn, Number(ev.tsMs))
    } else if (evTurn !== 0 && evTurn !== this.turnNo) {
      return false
    }
    if (typeof ev.seq === 'number' && ev.seq > this.appliedSeq) this.appliedSeq = ev.seq
    const d = ev.data ?? {}
    switch (ev.type) {
      case ET.TURN_START:
        // turnNo/startedAt 已在构造或 reset 时吸收
        break

      case ET.CONTEXT_INJECTED: {
        const block: AssistantBlock = {
          kind: 'context',
          name: String(d.name ?? ''),
          summary: String(d.summary ?? ''),
        }
        this.contextBlocks.push(block)
        // 首条步消息若已建立（事件乱序的防御情形），立刻补到头部
        if (!this.contextAttached && this.stepOrder.length > 0) {
          const first = this.steps.get(this.stepOrder[0])!
          first.blocks.unshift(block)
          first.version++
        }
        break
      }

      case ET.ASSISTANT_CHUNK: {
        const s = this.ensureStep(ev.stepNo)
        if (s.finalized) break // 定稿后忽略迟到 chunk
        const text = String(d.text ?? '')
        if (d.chunkType === 'reasoning-delta') {
          s.thinking += text
          if (!s.hasThinkingDraft) {
            s.hasThinkingDraft = true
            insertDraftBlock(s, { kind: 'thinking', text, state: 'running' })
          } else {
            updateThinkingDraft(s, s.thinking)
          }
        } else if (d.chunkType === 'text-delta') {
          s.text += text
          if (!s.hasTextDraft) {
            s.hasTextDraft = true
            insertDraftBlock(s, { kind: 'text', text })
          } else {
            updateTextDraft(s, s.text)
          }
        }
        s.version++
        break
      }

      case ET.ASSISTANT_MESSAGE: {
        const s = this.ensureStep(ev.stepNo)
        // 权威定稿：用 blocks 替换 reasoning/text 草稿；保留 context 与 interaction 块。
        const preserved = s.blocks.filter(
          (b) => b.kind === 'context' || b.kind === 'interaction',
        )
        const nextBlocks: AssistantBlock[] = [...preserved]
        s.toolKeys = []
        s.thinking = ''
        s.text = ''
        const arr = Array.isArray(d.blocks) ? d.blocks : []
        for (const b of arr) {
          const kind = b?.kind
          if (kind === 'reasoning') {
            const text = String(b.text ?? '')
            s.thinking += text
            nextBlocks.push({ kind: 'thinking', text, state: 'done' })
          } else if (kind === 'text') {
            const text = String(b.text ?? '')
            s.text += text
            nextBlocks.push({ kind: 'text', text })
          } else if (kind === 'tool-call') {
            const callId = String(b.callId ?? '')
            const rec = this.ensureTool(callId, String(b.name ?? ''), b.input)
            if (b.name) rec.toolName = String(b.name)
            if (b.input !== undefined) rec.input = b.input
            s.toolKeys.push(rec.key)
            nextBlocks.push({ kind: 'tool', id: rec.key })
          }
        }
        s.hasThinkingDraft = false
        s.hasTextDraft = false
        s.finalized = true
        s.blocks = nextBlocks
        s.version++
        break
      }

      case ET.TOOL_CALL: {
        const callId = String(d.callId ?? '')
        const rec = this.ensureTool(callId, String(d.name ?? ''), d.arguments)
        if (d.name) rec.toolName = String(d.name)
        rec.input = d.arguments ?? rec.input
        if (d.hostKey) rec.hostKey = String(d.hostKey)
        if (rec.status === 'done') break
        rec.status = 'running'
        const s = this.ensureStep(ev.stepNo)
        this.appendToolBlock(s, rec.key)
        s.version++
        break
      }

      case ET.TOOL_DECIDED: {
        const rec = this.tools.get(this.toolKey(String(d.callId ?? '')))
        if (rec) {
          if (d.decision === 'allow' || d.decision === 'deny') rec.decision = d.decision
          if (d.decidedBy === 'user' || d.decidedBy === 'auto') rec.decidedBy = d.decidedBy
        }
        break
      }

      case ET.TOOL_RESULT: {
        const callId = String(d.callId ?? '')
        const rec = this.ensureTool(callId, d.name ? String(d.name) : undefined)
        if (d.name) rec.toolName = String(d.name)
        rec.output = d.output ?? null
        const interrupted = d.interrupted === true
        const isError = d.isError === true || interrupted
        rec.isError = isError
        rec.interrupted = interrupted
        rec.status = (isError ? 'error' : 'done') as ToolStatus
        if (d.hostKey) rec.hostKey = String(d.hostKey)
        if (d.exitCode !== undefined && d.exitCode !== null) rec.exitCode = Number(d.exitCode)
        const s = this.ensureStep(ev.stepNo)
        this.appendToolBlock(s, rec.key)
        s.version++
        if (isMutationTool(rec.toolName)) this.lastMutationAt = Number(ev.tsMs)
        break
      }

      case ET.INTERACTION_REQUESTED: {
        const kind = d.kind === KIND_ASK ? 'ask' : 'confirm'
        const interactionId = String(d.interactionId ?? '')
        let block: AssistantBlock
        let data: ConfirmationCardData | AskCardData
        if (kind === 'confirm') {
          data = {
            confirmId: interactionId,
            toolName: String(d.toolName ?? ''),
            message: String(d.message ?? ''),
            tags: Array.isArray(d.tags) ? d.tags.map(String) : undefined,
            input: d.input ?? undefined,
          }
          block = { kind: 'interaction', data }
        } else {
          data = {
            askId: interactionId,
            question: String(d.question ?? ''),
            hint: d.hint != null ? String(d.hint) : undefined,
            options: Array.isArray(d.options)
              ? d.options.map(String).filter((x: string) => x.trim() !== '').slice(0, 4)
              : undefined,
          }
          block = { kind: 'interaction', data }
        }
        // 内联卡优先落在被确认工具所在步；找不到用事件 stepNo
        let target: StepDraft | undefined
        const callKey = d.callId ? this.toolKey(String(d.callId)) : undefined
        if (callKey) {
          for (const k of this.stepOrder) {
            if (this.steps.get(k)!.toolKeys.includes(callKey)) {
              target = this.steps.get(k)!
              break
            }
          }
        }
        target ??= this.ensureStep(ev.stepNo)
        if (!target.interactionIds.includes(interactionId)) {
          target.interactionIds.push(interactionId)
          target.blocks.push(block)
          target.version++
        }
        this.active = {
          kind,
          interactionId,
          callKey,
          data,
        }
        break
      }

      case ET.INTERACTION_RESOLVED: {
        const interactionId = String(d.interactionId ?? '')
        const interrupted = d.interrupted === true
        if (interrupted) this.hadInterruptedInteraction = true
        for (const k of this.stepOrder) {
          const s = this.steps.get(k)!
          const idx = s.blocks.findIndex(
            (b) => b.kind === 'interaction' && interactionIdOf(b.data) === interactionId,
          )
          if (idx >= 0) {
            const old = s.blocks[idx]
            if (old.kind !== 'interaction') continue
            const prev = old.data
            if (d.kind === KIND_ASK || 'askId' in prev) {
              const skipped = d.skipped === true || interrupted
              const data = prev as AskCardData
              data.resolvedAnswer = skipped ? null : (d.answer != null ? String(d.answer) : '')
            } else {
              const data = prev as ConfirmationCardData
              data.resolved = d.approved === true
            }
            s.version++
            break
          }
        }
        if (this.active?.interactionId === interactionId) this.active = null
        break
      }

      case ET.TURN_END: {
        this.phase = 'ended'
        this.endedAt = Number(ev.tsMs)
        this.reason = String(d.reason ?? REASON.ERROR)
        if (d.errorMessage) this.errorMessage = String(d.errorMessage)
        this.endNote = this.computeEndNote()
        break
      }

      // step/* / request/header / compaction / terminal-mirror / error / end-seed：
      // 不影响对话投影（工具计数/观测页/终端镜像另有消费者）。
      default:
        break
    }
    return true
  }

  private computeEndNote(): LiveTurnView['endNote'] {
    switch (this.reason) {
      case REASON.INTERRUPTED:
        return { tone: 'interrupted', text: INTERRUPTED_HINT }
      case REASON.ABORTED: {
        // 空回复停止时给出定稿气泡文案；已有任何正文/工具则不重复提示
        const hasContent = [...this.tools.values()].length > 0
          || this.stepOrder.some((k) => {
            const s = this.steps.get(k)!
            return s.text.trim() !== '' || s.thinking.trim() !== ''
          })
        return hasContent ? undefined : { tone: 'stopped', text: STOPPED_HINT }
      }
      case REASON.ERROR:
        return {
          tone: 'error',
          text: this.errorMessage ? `本轮执行出错：${this.errorMessage}` : '本轮执行出错',
        }
      default:
        return undefined
    }
  }

  snapshot(): LiveTurnView {
    const isLast = (idx: number) => idx === this.stepOrder.length - 1
    const stepMessages: LiveStepMessage[] = this.stepOrder.map((k, idx) => {
      const s = this.steps.get(k)!
      const running = this.phase === 'running' && isLast(idx) && !s.finalized
      // version/running 未变（典型：其它步在流式，本步已定稿）→ 原样复用旧视图，
      // 让 AssistantMessage/ToolCard 的 memo 真正生效，零重复 Markdown 解析。
      const cached = this.stepViewCache.get(k)
      if (cached && cached.version === s.version && cached.running === running) {
        return cached.view
      }
      // 草稿块的 thinking 状态随 turn 状态定格
      const blocks = s.blocks.map((b) =>
        b.kind === 'thinking' && !s.finalized
          ? { ...b, state: running ? 'running' : 'done' } as AssistantBlock
          : b,
      )
      // 浅克隆记录：ingest 对 rec 是原地突变（status/output/isError…），而 ToolCard
      // 是 memo 组件，引用不变就跳过重渲染——这里仅在步版本变化时重建并给新引用，
      // 既保证工具结果能刷出来，也让未变化步的工具卡跨帧被 memo 跳过。
      const tools = s.toolKeys
        .map((key) => this.tools.get(key))
        .filter((r): r is LiveToolCallRecord => !!r)
        .map((r) => ({ ...r }))
      const view: LiveStepMessage = {
        id: `live-${this.turnNo}-s${s.stepNo}`,
        role: 'assistant',
        stepNo: s.stepNo,
        text: s.text,
        thinking: s.thinking,
        thinkingState: running && s.hasThinkingDraft ? 'running' : 'done',
        pending: running && (s.hasTextDraft || s.hasThinkingDraft),
        streaming: !s.finalized,
        blocks,
        tools: tools.length > 0 ? tools : undefined,
        time: this.startedAt,
      }
      this.stepViewCache.set(k, { version: s.version, running, view })
      return view
    })

    const toolsByKey: Record<string, LiveToolCallRecord> = {}
    for (const [key, rec] of this.tools) toolsByKey[key] = { ...rec }

    return {
      turnNo: this.turnNo,
      phase: this.phase,
      reason: this.reason,
      errorMessage: this.errorMessage,
      startedAt: this.startedAt,
      endedAt: this.endedAt,
      appliedSeq: this.appliedSeq,
      stepMessages,
      toolsByKey,
      activeInteraction: this.active ?? null,
      endNote: this.endNote,
      lastMutationAt: this.lastMutationAt,
      hadInterruptedInteraction: this.hadInterruptedInteraction || undefined,
    }
  }

  /** 从不可变快照还原一个可继续 ingest 的 folder（纯函数 applyEvent 门面使用）。 */
  static fromView(view: LiveTurnView): LiveTurnFolder {
    const f = new LiveTurnFolder(view.turnNo, view.startedAt)
    f.phase = view.phase
    f.reason = view.reason
    f.errorMessage = view.errorMessage
    f.endedAt = view.endedAt
    f.endNote = view.endNote
    f.lastMutationAt = view.lastMutationAt
    f.hadInterruptedInteraction = view.hadInterruptedInteraction === true
    f.appliedSeq = view.appliedSeq
    f.active = view.activeInteraction ?? null

    for (const msg of view.stepMessages) {
      const s = f.ensureStep(msg.stepNo)
      s.thinking = msg.thinking ?? ''
      s.text = msg.text ?? ''
      s.finalized = !msg.streaming
      s.hasThinkingDraft = !s.finalized && (msg.thinking?.length ?? 0) > 0
      s.hasTextDraft = !s.finalized && (msg.text?.length ?? 0) > 0
      s.blocks = (msg.blocks ?? []).map(cloneBlock)
      for (const blk of s.blocks) {
        if (blk.kind === 'tool') s.toolKeys.push(blk.id)
        if (blk.kind === 'interaction') s.interactionIds.push(interactionIdOf(blk.data))
      }
    }
    for (const [key, rec] of Object.entries(view.toolsByKey)) {
      f.tools.set(key, { ...rec })
    }
    // context 块已挂在首条步消息里，rehydrate 后不会再来 context 事件
    f.contextAttached = true
    return f
  }
}

function interactionIdOf(data: ConfirmationCardData | AskCardData): string {
  return 'confirmId' in data ? data.confirmId : data.askId
}

/** 块深拷贝（interaction data 必须复制，resolved 时的原地修改不能污染旧快照）。 */
function cloneBlock(b: AssistantBlock): AssistantBlock {
  if (b.kind === 'interaction') {
    return { kind: 'interaction', data: { ...b.data } as ConfirmationCardData | AskCardData }
  }
  return { ...b }
}

/** 在合适位置插入草稿块（thinking 在 text/tool/interaction 之前；text 紧随其后）。 */
function insertDraftBlock(s: StepDraft, block: AssistantBlock): void {
  if (block.kind === 'thinking') {
    // thinking 放在最后一个 context 块之后
    let idx = 0
    while (idx < s.blocks.length && s.blocks[idx].kind === 'context') idx++
    s.blocks.splice(idx, 0, block)
  } else {
    // text 放在 thinking/context 之后、tool/interaction 之前
    let idx = 0
    while (idx < s.blocks.length
      && (s.blocks[idx].kind === 'context' || s.blocks[idx].kind === 'thinking')) {
      idx++
    }
    s.blocks.splice(idx, 0, block)
  }
}

function updateThinkingDraft(s: StepDraft, text: string): void {
  const b = s.blocks.find((x) => x.kind === 'thinking')
  if (b && b.kind === 'thinking') b.text = text
}

function updateTextDraft(s: StepDraft, text: string): void {
  const b = s.blocks.find((x) => x.kind === 'text')
  if (b && b.kind === 'text') b.text = text
}

// ---------------------------------------------------------------------------
// 对外纯函数
// ---------------------------------------------------------------------------

/**
 * 从 snapshot（升序事件）定位并构建当前 live turn 的长期 folder。
 * - 存在未闭合 turn（最后 turn/start 后无 turn/end）→ running folder；
 * - 最后 turn 以 interrupted 闭合 → ended folder（冷恢复现场，旧表无该轮投影）；
 * - 其余情况（无 turn / 正常闭合）→ null（交给历史基线）；
 * - snapshot 缺失 turn/start（极端长 turn 截断）→ 以事件中最小 turnNo 容错投影。
 */
export function initLiveFolder(events: readonly SessionEvent[]): LiveTurnFolder | null {
  if (!events || events.length === 0) return null

  let lastStartIdx = -1
  let turnNo = 0
  let startedAt = 0
  for (let i = 0; i < events.length; i++) {
    if (events[i].type === ET.TURN_START) {
      lastStartIdx = i
      turnNo = Number(events[i].data?.turn ?? events[i].turnNo) || 0
      startedAt = Number(events[i].tsMs)
    }
  }

  if (lastStartIdx >= 0) {
    let endReason: string | null = null
    for (let i = lastStartIdx + 1; i < events.length; i++) {
      if (events[i].type === ET.TURN_END
        && Number(events[i].data?.turn ?? events[i].turnNo) === turnNo) {
        endReason = String(events[i].data?.reason ?? REASON.ERROR)
      }
    }
    if (endReason !== null && endReason !== REASON.INTERRUPTED) return null
    const f = new LiveTurnFolder(turnNo, startedAt)
    for (let i = lastStartIdx; i < events.length; i++) f.ingest(events[i])
    return f
  }

  // 容错：没有 turn/start（窗口截断）。存在未闭合活动则以最小 turnNo 续投。
  let minTurn = Number.MAX_SAFE_INTEGER
  let hasOpenActivity = false
  for (const ev of events) {
    if (ev.type === ET.TURN_END) continue
    if (ev.turnNo && ev.turnNo > 0) {
      minTurn = Math.min(minTurn, ev.turnNo)
      hasOpenActivity = true
    }
  }
  if (!hasOpenActivity) return null
  const first = events.find((ev) => ev.turnNo === minTurn)
  const f = new LiveTurnFolder(minTurn, first ? Number(first.tsMs) : 0)
  for (const ev of events) {
    const t = Number(ev.data?.turn ?? ev.turnNo) || 0
    if (t === 0 || t === minTurn) f.ingest(ev)
  }
  return f
}

/** 从 snapshot 重建当前 live turn 的不可变视图（initLiveFolder 的纯视图便捷版）。 */
export function initLiveTurn(events: readonly SessionEvent[]): LiveTurnView | null {
  return initLiveFolder(events)?.snapshot() ?? null
}

/**
 * 增量折叠一条 live 事件，返回新视图（无变化时返回原引用）。
 * view 为 null 时，仅 turn/start 会开启新视图；其余事件忽略（应已由 snapshot 覆盖）。
 * seq 去重：ev.seq &lt;= appliedSeq 的事件原样丢弃。
 *
 * 注：runtime 热路径请长期持有 {@link LiveTurnFolder} 直接 ingest，避免每事件
 * fromView 重建；本函数主要用于测试与一次性 reduce。
 */
export function applyEvent(view: LiveTurnView | null, ev: SessionEvent): LiveTurnView | null {
  if (!ev || typeof ev.seq !== 'number') return view

  if (view === null) {
    if (ev.type !== ET.TURN_START) return null
    const f = new LiveTurnFolder(
      Number(ev.data?.turn ?? ev.turnNo) || 0,
      Number(ev.tsMs),
    )
    f.ingest(ev)
    return f.snapshot()
  }

  if (ev.seq <= view.appliedSeq) return view
  const f = LiveTurnFolder.fromView(view)
  if (!f.ingest(ev)) return view
  return f.snapshot()
}
