import { createContext, cloneElement, isValidElement, type HTMLAttributes, type ReactNode, memo, useContext, useMemo, useRef, useState } from 'react'
import {
  Copy, Check, ChevronDown,
  Inbox, Layers,
} from 'lucide-react'
import type { ChatAttachment } from '@/api/agentApi'
import type { FileTag } from './InputBar'
import { getFileIcon } from '@/utils/fileIcons'
import { StreamingMarkdown } from './StreamingMarkdown'
import { ToolCard, type ToolCallRecord } from './ToolCard'
import { detectConnectBlock } from './HostConnectBlock'
import {
  ConfirmationCard, AskCard, WaitingInteractionRow,
  type ConfirmationCardData, type AskCardData,
} from './InteractionCards'

/**
 * 聊天气泡 —— 1:1 复刻 dsh ui-conversation MessageItem/ReasoningRow/StatsLine。
 * Translate：dsh 的 MessageText/JsonBlock/primitives → 简化实现；
 * markdown 渲染用 react-markdown + dsh 排版 token（message.css .md-body）；
 * 思考折叠用 <details>（对应 ReasoningRow）；复制/时间用 antd 图标。
 */

/**
 * 正文里"裸路径"识别（#7）：模型常把当前目录等路径写进普通句子（非 code、非产物），
 * dsh 会把它们渲染成灰色内联 chip。这里把 Windows 盘符路径 / Unix 绝对路径包成 code.md-pathChip。
 * 已有的 code/链接原样保留，不重复包裹。
 */
const PATH_RE = /[A-Za-z]:\\[^\s，。；、）)】」』"'`]+|(?:^|\s)(\/[\w.][\w./-]{2,})/g

function splitPaths(text: string): ReactNode[] {
  const parts: ReactNode[] = []
  let last = 0
  let m: RegExpExecArray | null
  let key = 0
  PATH_RE.lastIndex = 0
  while ((m = PATH_RE.exec(text)) !== null) {
    const matched = m[0]
    // Unix 分支可能带前导空格，把空格留在 chip 外。
    const lead = matched.startsWith(' ') ? ' ' : ''
    const path = lead ? matched.slice(1) : matched
    if (m.index + lead.length > last) parts.push(text.slice(last, m.index + lead.length))
    parts.push(<code key={key++} className="md-pathChip">{path}</code>)
    last = m.index + matched.length
  }
  if (parts.length === 0) return [text]
  if (last < text.length) parts.push(text.slice(last))
  return parts
}

/** 递归遍历 markdown 节点，把字符串叶子里的裸路径替换成 chip；code/a/button 内部不动。 */
function chipify(node: ReactNode): ReactNode {
  if (typeof node === 'string') return splitPaths(node)
  if (Array.isArray(node)) return node.map((child, i) => <span key={i}>{chipify(child)}</span>)
  if (isValidElement(node)) {
    const type = node.type as unknown
    if (type === 'code' || type === 'a' || type === 'button' || type === 'pre') return node
    const children = (node.props as { children?: ReactNode }).children
    return cloneElement(node, undefined, chipify(children))
  }
  return node
}

/**
 * 块级代码上下文：react-markdown 的 code 组件同时服务行内 code 与 <pre> 内
 * 的代码块（v10 不再传 inline 标记）——用 pre 组件注入上下文来区分。
 * 只有行内 code 参与 file-mention；代码块原样渲染。
 */
const BlockCodeContext = createContext(false)

export interface ChatStats {
  tokens: number
  ms: number
}

/**
 * 助手时间线块：正文段 / 工具调用段 / 思考段 / 上下文注入段，按 SSE 到达顺序穿插排列
 * （dsh 里上下文注入行、Think 行、工具卡、正文是按时间线交错的，不是把所有工具堆在答案后）。
 * - text 持有该段正文（流式增量追加到末尾 text 块）
 * - tool 按 id 引用 tools 表
 * - thinking 持有"Think"段（与旧的整体 thinking 折叠互不干扰；thinking 块是流式增量拼出来的完整片段）
 * - context 是「上下文注入 · <name> · <summary>」行
 * - interaction 是人工确认 / ask_user 交互卡：按事件到达顺序内联在时间线里，
 *   处理后就地折叠成一行摘要，随内容一起上滚，不再钉在消息流底部。
 */
export type AssistantBlock =
  | { kind: 'text'; text: string }
  | { kind: 'tool'; id: string }
  | { kind: 'thinking'; text: string; state: 'done' | 'running' }
  | { kind: 'context'; name: string; summary: string }
  | { kind: 'interaction'; data: ConfirmationCardData | AskCardData }

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  time?: number
  /** 用户消息携带的本地附件（拖拽上传，展示为 chip） */
  attachments?: ChatAttachment[]
  /** 用户消息携带的服务器文件 @path 标签 */
  fileTags?: FileTag[]
  /** 思考过程（assistant 专用，可折叠） */
  thinking?: string
  /** 'running' 时思考行顶部扫光 */
  thinkingState?: 'done' | 'running'
  stats?: ChatStats
  /** 助手生成中（闪烁光标） */
  pending?: boolean
  /** 步草稿未权威定稿（liveTurnProjector 输出；服务器基线消息缺省为 false） */
  streaming?: boolean
  /** 本轮的工具调用表（tool_call/tool_result 累积，按 id 被 blocks 引用） */
  tools?: ToolCallRecord[]
  /** 正文/工具的有序时间线（assistant 专用）；缺省时回退为 text + tools 末尾聚合渲染。 */
  blocks?: AssistantBlock[]
  error?: { title: string; message: string; kind?: 'error' | 'warn'; code?: string }
}

/** 用户气泡内的引用 chip（/skill @subagent token 装饰）。 */
function projectUserText(text: string): ReactNode {
  const re = /(^|\s)([/@][\w-]+)(?=\s|$)/g
  const parts: ReactNode[] = []
  let cursor = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    const tokenStart = m.index + (m[1]?.length ?? 0)
    const label = m[2] ?? ''
    if (tokenStart > cursor) parts.push(text.slice(cursor, tokenStart))
    parts.push(
      <span key={tokenStart} className="msg-refChip" data-ref-chip={label.startsWith('@') ? 'subagent' : 'skill'}>
        {label}
      </span>,
    )
    cursor = tokenStart + label.length
  }
  if (parts.length === 0) return text
  if (cursor < text.length) parts.push(text.slice(cursor))
  return <>{parts}</>
}

function formatTime(ts: number): string {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 用户消息（右对齐气泡 + 时间/复制操作行）。 */
export const UserMessage = memo(function UserMessage({ text, time, pending, attachments, fileTags }: {
  text: string
  time?: number
  pending?: boolean
  attachments?: ChatAttachment[]
  fileTags?: FileTag[]
}) {
  const [copied, setCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const copy = (): void => {
    void navigator.clipboard.writeText(text)
    setCopied(true)
    if (copiedTimer.current !== null) clearTimeout(copiedTimer.current)
    copiedTimer.current = setTimeout(() => setCopied(false), 1600)
  }

  const hasChips = (attachments !== undefined && attachments.length > 0) || (fileTags !== undefined && fileTags.length > 0)

  return (
    <div className="msg-userRow" data-pending-steering={pending || undefined}>
      <div className="msg-userStack">
        {hasChips && (
          <div className="msg-userChips">
            {fileTags?.map((t) => {
              const fi = getFileIcon(t.name, t.isDir)
              return (
                <span key={`tag-${t.path}`} className="msg-userChip" data-file-tag title={t.path}>
                  <span className="msg-userChipIcon" style={{ color: fi.color }}>{fi.icon}</span>
                  <span className="msg-userChipName">{t.name}</span>
                </span>
              )
            })}
            {attachments?.map((a) => {
              const fi = getFileIcon(a.name)
              return (
                <span key={`att-${a.path}`} className="msg-userChip" data-attachment title={a.path}>
                  <span className="msg-userChipIcon" style={{ color: fi.color }}>{fi.icon}</span>
                  <span className="msg-userChipName">{a.name}</span>
                </span>
              )
            })}
          </div>
        )}
        <div className="msg-bubble">{projectUserText(text)}</div>
      </div>
      <div className="msg-actions">
        {time !== undefined && <span className="msg-time">{formatTime(time)}</span>}
        {text.length > 0 && (
          <button
            type="button"
            className={copied ? 'msg-copyBtn msg-copyBtn--copied' : 'msg-copyBtn'}
            aria-label={copied ? '已复制' : '复制'}
            title={copied ? '已复制' : '复制'}
            onClick={copy}
          >
            {copied ? <Check style={{ fontSize: 13 }} /> : <Copy style={{ fontSize: 13 }} />}
          </button>
        )}
      </div>
    </div>
  )
})

/** 单行截断（上下文注入 / Think 摘要共享）。 */
function truncateSingle(s: string, max = 96): string {
  if (!s) return ''
  const one = s.replace(/\s+/g, ' ').trim()
  return one.length > max ? `${one.slice(0, max - 1)}…` : one
}

/**
 * 上下文注入行（dsh "AGENTS.md, CLAUDE.md" 这类单行）。
 * 统一格式：[图标] · 名字 · 摘要（单行截断）。
 */
function ContextRow({ name, summary }: { name: string; summary: string }) {
  const title = summary ? `${name} — ${summary}` : name
  return (
    <div className="ctx-root" title={title}>
      <span className="ctx-icon" aria-hidden>
        <Inbox style={{ fontSize: 12 }} />
      </span>
      <span className="ctx-name">{truncateSingle(name, 48)}</span>
      {summary && (
        <>
          <span className="ctx-dot" aria-hidden>·</span>
          <span className="ctx-summary">{truncateSingle(summary, 80)}</span>
        </>
      )}
    </div>
  )
}

/**
 * 本轮上下文芯片：开头连续多条 context 注入（内置工具集 / 系统提示…）
 * 裸名+空计数属于调试视角，默认收成一颗芯片，不占消息首屏；点开再回看各行。
 */
function ContextGroup({ items }: { items: Array<{ name: string; summary: string }> }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="ctxg-root" data-open={open || undefined}>
      <button
        type="button"
        className="ctxg-chip"
        aria-expanded={open}
        onClick={() => setOpen(v => !v)}
      >
        <span className="ctxg-icon" aria-hidden>
          <Layers style={{ fontSize: 13 }} />
        </span>
        <span className="ctxg-label">本轮上下文</span>
        <span className="ctxg-count">{items.length} 条</span>
        <ChevronDown className="ctxg-chevron" style={{ fontSize: 11 }} aria-hidden />
      </button>
      {open && (
        <div className="ctxg-body">
          {items.map((it, i) => <ContextRow key={i} name={it.name} summary={it.summary} />)}
        </div>
      )}
    </div>
  )
}

/**
 * Think 行（dsh "Think · The user is asking…" 行）。
 * 统一格式：[running=spinner / done=灯泡图标] · "Think" 标签 · 正文首行摘要（单行截断）。
 * 整行可点击展开完整思考；展开后正文独立段落显示。
 */
function ThinkRow({ text, state }: { text: string; state?: 'done' | 'running' }) {
  const [open, setOpen] = useState(false)
  const running = state === 'running'
  const summary = truncateSingle(text.replace(/\n.*/s, '') || (running ? '思考中…' : '已思考'), 120)
  return (
    <details className="thk-root" data-state={running ? 'running' : undefined} open={open}>
      <summary
        className="thk-row"
        onClick={(e) => { e.preventDefault(); setOpen(v => !v) }}
      >
        <span
          className={running ? 'thk-icon tm-fx tm-ico-think' : 'thk-icon tm-fx'}
          aria-hidden
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
            <path className="i-glass" d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5" />
            <path d="M9 18h6" />
            <path d="M10 22h4" />
            <path className="i-wire" d="M12 13v-3M12 10 9.7 7.7M12 10l2.3-2.3" />
          </svg>
        </span>
        <span className="thk-label">Think</span>
        <span className="thk-sep" aria-hidden />
        <span
          className="thk-summary"
          data-follow-end={running || undefined}
          data-text={summary}
        >{summary}</span>
        <span className="thk-chevron" aria-hidden>
          <ChevronDown style={{ fontSize: 11 }} />
        </span>
      </summary>
      <div className="thk-body">{text}</div>
    </details>
  )
}

/** 会话统计行（StatsLine）。 */
function StatsLine({ stats }: { stats: ChatStats }) {
  return (
    <div className="stats-root">
      {stats.tokens.toLocaleString()} tokens<span className="stats-sep">·</span>{(stats.ms / 1000).toFixed(2)}s
    </div>
  )
}

/** 错误 / 封顶提示行（TurnError / TurnMaxTokens）。 */
function TurnErrorRow({ error }: { error: NonNullable<ChatMessage['error']> }) {
  const warn = error.kind === 'warn'
  return (
    <div className="turn-errorRow" role="status">
      <span className={`turn-errorDot ${warn ? 'warn' : ''}`} />
      <div className="turn-errorCopy">
        <span className={`turn-errorTitle ${warn ? 'warn' : ''}`}>{error.title}</span>
        <span className="turn-errorMessage">{error.message}</span>
      </div>
      {error.code !== undefined && <code className="turn-errorCode">{error.code}</code>}
    </div>
  )
}

/** 代码块：hover 右上角浮现复制按钮，复制后短暂对勾（dsh markdown code-block chrome）。 */
function CodeBlock({ children, ...props }: { children?: ReactNode } & React.HTMLAttributes<HTMLPreElement>) {
  const preRef = useRef<HTMLPreElement>(null)
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const copy = (): void => {
    const text = preRef.current?.textContent ?? ''
    void navigator.clipboard.writeText(text)
    setCopied(true)
    if (timer.current !== null) clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 1600)
  }

  return (
    <BlockCodeContext.Provider value={true}>
      <div className="md-codeBlock">
        <pre ref={preRef} {...props}>{children}</pre>
        <button
          type="button"
          className={copied ? 'md-codeCopy md-codeCopy--copied' : 'md-codeCopy'}
          aria-label={copied ? '已复制' : '复制代码'}
          title={copied ? '已复制' : '复制代码'}
          onClick={copy}
        >
          {copied ? <Check style={{ fontSize: 12 }} /> : <Copy style={{ fontSize: 12 }} />}
        </button>
      </div>
    </BlockCodeContext.Provider>
  )
}

/** 助手消息（左对齐：上下文注入行 + Think 行 + markdown 主体 + 工具卡 + 统计行；整轮 Working 指示在对话流层）。 */
export const AssistantMessage = memo(function AssistantMessage({ text, thinking, thinkingState, stats, error, tools, blocks, pending, streaming, openPath, canResumeTurn = true, onResumeTurn }: {
  text: string
  thinking?: string
  thinkingState?: 'done' | 'running'
  stats?: ChatStats
  pending?: boolean
  streaming?: boolean
  tools?: ToolCallRecord[]
  blocks?: AssistantBlock[]
  openPath?: (path: string) => void
  error?: ChatMessage['error']
  /** AI 空闲时连接阻断卡才可点「继续执行」。 */
  canResumeTurn?: boolean
  /** 连接成功后的续跑回调（自动发送"已连接，请继续刚才的任务"）。 */
  onResumeTurn?: () => void
}) {
  // 稳定组件表：引用钉死，避免 markdown 每次流式增量全量重渲染。
  const markdownComponents = useMemo(() => ({
    pre: ({ node: _node, children, ...props }: { node?: unknown; children?: ReactNode }) => (
      <CodeBlock {...(props as HTMLAttributes<HTMLPreElement>)}>{children}</CodeBlock>
    ),
    // 段落 / 列表项里的裸路径包成 chip（#7）。
    p: ({ children }: { children?: ReactNode }) => <p>{chipify(children)}</p>,
    li: ({ children }: { children?: ReactNode }) => <li>{chipify(children)}</li>,
    code: ({ node: _node, className, children, ...props }: { node?: unknown; className?: string; children?: ReactNode }) => {
      if (useContext(BlockCodeContext)) {
        return <code className={className} {...props}>{children}</code>
      }
      return <code className={className} {...props}>{children}</code>
    },
  }), [])

  // 同一轮多把工具（run_ssh/sftp_*）同时撞上连接阻断时，只让首个失败工具挂阻断卡，避免重复引导。
  const firstBlockedToolId = useMemo(
    () => tools?.find((t) => detectConnectBlock(t) !== null)?.id,
    [tools],
  )

  // live=正文正在流式：streaming（步未定稿）且 pending（turn running、最后一步有草稿）。
  // 注意中断/停止后 TURN_END 不 finalize step（streaming 仍 true），但 pending 已翻 false，
  // 此时走定稿路径整篇全量解析一次（FR-2 自愈）。
  const renderMarkdown = (body: string, key: string | number, live: boolean): ReactNode => (
    <div className="md-body" key={key}>
      {live
        ? <StreamingMarkdown key="stream" text={body} streaming components={markdownComponents} />
        : <StreamingMarkdown key="settled" text={body} streaming={false} components={markdownComponents} />}
    </div>
  )

  return (
    <div className="msg-assistantRow">
      <div className="msg-assistantStack">
        {error !== undefined && <TurnErrorRow error={error} />}
        {/* 任务计划已收口为 composer 座顶部唯一的 TodoDock（全会话一个实例），
            消息流不再逐条渲染 TodoProgress；todo_write 工具行本身仍保留在时间线内。 */}
        {blocks !== undefined && blocks.length > 0 ? (
          /* 时间线：上下文注入 / Think / 正文 / 工具卡 按 SSE 到达顺序穿插。 */
          blocks.map((b, i) => {
            if (b.kind === 'context') {
              // 连续的 context 注入由组首统一收进「本轮上下文」芯片，后续行跳过。
              if (i > 0 && blocks[i - 1].kind === 'context') return null
              const items: Array<{ name: string; summary: string }> = []
              let j = i
              while (j < blocks.length) {
                const cb = blocks[j]
                if (cb.kind !== 'context') break
                items.push({ name: cb.name, summary: cb.summary })
                j++
              }
              return <ContextGroup key={`ctxg-${i}`} items={items} />
            }
            if (b.kind === 'thinking') return <ThinkRow key={`thk-${i}`} text={b.text} state={b.state} />
            if (b.kind === 'text') {
              return b.text.length > 0
                ? renderMarkdown(b.text, `txt-${i}`, streaming === true && pending === true)
                : null
            }
            if (b.kind === 'tool') {
              const rec = tools?.find(t => t.id === b.id)
              return rec !== undefined
                ? (
                  <ToolCard
                    key={b.id}
                    record={rec}
                    openPath={openPath}
                    showHostBlock={firstBlockedToolId === b.id}
                    canResumeTurn={canResumeTurn}
                    onResumeTurn={onResumeTurn}
                  />
                )
                : null
            }
            if (b.kind === 'interaction') {
              // 等待态：完整卡片在底部 composer 接管位，时间线只留一行活动状态；
              // 已决议：折叠摘要行（可展开回看）；本轮结束仍未处理：置灰一行。
              const ended = pending !== true
              if ('confirmId' in b.data) {
                const d = b.data
                if (d.resolved === undefined && !ended) {
                  return <WaitingInteractionRow key={d.confirmId} kind="confirm" toolName={d.toolName} />
                }
                return <ConfirmationCard key={d.confirmId} {...d} />
              }
              const d = b.data
              if (d.resolvedAnswer === undefined && !ended) {
                return <WaitingInteractionRow key={d.askId} kind="ask" question={d.question} />
              }
              return <AskCard key={d.askId} {...d} />
            }
            return null
          })
        ) : (
          /* 兜底：无 blocks 时按旧顺序（正文 → 工具堆底），thinking 仍用旧整体折叠。 */
          <>
            {thinking !== undefined && thinking.length > 0 && (
              <ThinkRow text={thinking} state={thinkingState} />
            )}
            {text.length > 0 && renderMarkdown(text, 'fallback', streaming === true && pending === true)}
            {tools !== undefined && tools.length > 0 && (
              <div className="msg-tools">
                {tools.map(tool => (
                  <ToolCard
                    key={tool.id}
                    record={tool}
                    openPath={openPath}
                    showHostBlock={firstBlockedToolId === tool.id}
                    canResumeTurn={canResumeTurn}
                    onResumeTurn={onResumeTurn}
                  />
                ))}
              </div>
            )}
          </>
        )}
        {stats !== undefined && <StatsLine stats={stats} />}
      </div>
    </div>
  )
})
