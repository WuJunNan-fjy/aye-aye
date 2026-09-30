import { memo, useState, type KeyboardEvent, type ReactNode } from 'react'
import clsx from 'clsx'
import {
  Check, X, CircleCheck, CircleX, CircleMinus,
  ChevronDown, Pencil, CircleHelp,
} from 'lucide-react'
import { resolveConfirmation, resolveAsk } from '@/api/agentApi'

/**
 * 交互卡片（确认 / 询问）—— 对应 AgentEvent 的 confirmation_request / ask_user。
 *
 * <p>1:1 对齐 dsh 的 composer-takeover 机制（ui-conversation ApprovalPanel /
 * ui-user-questions QuestionComposer）：AgentLoop 暂停等用户回传期间，等待态的
 * <b>完整浮卡接管底部 composer 座、整体替换 InputBar</b>（同尺寸、同位，内容互换
 * 而非布局跳动）；用户决议后浮卡离开、输入框回来。
 * <ul>
 *   <li><b>时间线</b>：等待态只保留一行紧凑状态（琥珀点「等待审批 · 工具名」 /
 *       问号「AI 提问 · 问题摘要」）；决议后落为可展开的折叠摘要（DoneShell）；
 *       本轮异常结束未处理则置灰一行（历史重放走同一套）。</li>
 *   <li><b>composer 座</b>：ComposerApprovalCard（琥珀通栏 + 理由/命令滚动区 +
 *       拒绝/允许一次）、ComposerQuestionCard（eyebrow + 编号选项 + 自定义作答 +
 *       跳过/提交），回传成功后由父组件把决议写回时间线 block。</li>
 * </ul>
 */

export interface ConfirmationCardData {
  confirmId: string
  toolName: string
  message: string
  tags?: string[]
  input?: unknown
  /**
   * 历史重放 / 实时决议回写标记：携带布尔决议结果。
   * true=已批准，false=已拒绝；缺省（undefined）=等待态（实时接管卡或结束未处理）。
   */
  resolved?: boolean
}

export interface AskCardData {
  askId: string
  question: string
  hint?: string
  /** 后端结构化选项（ask_user 工具的 options 参数）；缺省时前端尝试从问题文本兜底解析。 */
  options?: string[]
  /**
   * 历史重放 / 实时决议回写标记：
   * string=当时的作答原文，null=已跳过，undefined=等待态。
   */
  resolvedAnswer?: string | null
}

/** tags（[A-Z_]+）→ 中文标签文案（前端展示用）。 */
const TAG_LABELS: Record<string, string> = {
  GIT_DATA_LOSS: '可能丢失 Git 提交',
  GIT_HISTORY_REWRITE: '重写 Git 历史',
  RECURSIVE_FORCE_DELETE: '递归删除文件',
  SINGLE_FILE_DELETE: '删除文件',
  DB_DROP: '删除数据库表',
  DB_MASS_DELETE: '批量删除数据',
  K8S_DELETE: '删除集群资源',
  TF_DESTROY: '销毁基础设施',
  PATH_ESCAPE_WRITE: '越界写入',
  PATH_ESCAPE_READ: '越界读取',
  COMMAND_SUBSTITUTION_BYPASS: '命令间接执行',
  SHELL_CODE_INJECTION: '命令注入',
  POWERSHELL_IEX: '远程下载执行',
}

/** 从 input 里抽一个可读摘要（展示候选参数回退到 JSON）。 */
function inputPreview(input: unknown): string {
  if (input === undefined || input === null) return ''
  if (typeof input === 'string') return input
  try {
    return String(JSON.stringify(input, null, 2))
  } catch {
    return String(input)
  }
}

/**
 * 从审批 input 抽取 shell 命令（bash 类工具 args 携带 command 字段）；
 * 取不到时返回空串，审批卡改走「参数预览」折叠区。
 */
function commandOf(input: unknown): string {
  if (input === undefined || input === null || typeof input !== 'object') return ''
  const cmd = (input as Record<string, unknown>).command
  return typeof cmd === 'string' ? cmd.trim() : ''
}

function dedupe(items: string[]): string[] {
  const out: string[] = []
  for (const it of items) {
    const t = it.trim()
    if (t !== '' && !out.includes(t)) out.push(t)
  }
  return out
}

/**
 * 模型没给结构化 options 时，从问题/提示文本兜底解析编号选项。
 * 支持：行内「1) … 2) …」、行首「1. / 1、 / 1:」编号；要求编号从 1 起连续，避免把
 * 「1.2G」这类正文误判为选项。
 */
function parseNumberedOptions(...texts: Array<string | undefined>): string[] {
  const text = texts.filter(Boolean).join('\n')
  if (text === '') return []

  // 1) 逐行匹配行首编号
  const lineHits: string[] = []
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*(\d{1,2})\s*[)）.、:：]\s*(.+?)\s*$/)
    if (m) lineHits.push(m[2])
  }
  if (lineHits.length >= 2) return dedupe(lineHits).slice(0, 4)

  // 2) 行内编号：仅认「数字 + )/）」（小数点会误伤 1.2G 之类）
  const markers = [...text.matchAll(/(\d{1,2})\s*[)）]/g)]
  if (markers.length >= 2) {
    const nums = markers.map(m => Number(m[1]))
    const sequential = nums.every((n, i) => n === i + 1)
    if (!sequential) return []
    const segs: string[] = []
    for (let i = 0; i < markers.length; i++) {
      const start = (markers[i].index ?? 0) + markers[i][0].length
      const end = i + 1 < markers.length ? (markers[i + 1].index ?? text.length) : text.length
      segs.push(text.slice(start, end).replace(/^[\s:：、.）)]+/, '').trim())
    }
    if (segs.length >= 2 && segs.every(s => s !== '')) return dedupe(segs).slice(0, 4)
  }
  return []
}

/** 中文输入法组字中（Enter 上屏候选词）时不触发作答提交。 */
function isComposing(event: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>): boolean {
  // keyCode 229 是老引擎在 isComposing 缺失时仍会发出的组字信号。
  // oxlint-disable-next-line typescript/no-deprecated
  return event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
}

const DANGER_TAG = /DELETE|ESCAPE|GIT|DESTROY|MASS|BLOCKED|INJECTION|IEX/

/** 单行截断（等待行摘要用）。 */
function truncateSingle(s: string, max = 64): string {
  const one = s.replace(/\s+/g, ' ').trim()
  return one.length > max ? `${one.slice(0, max - 1)}…` : one
}

/**
 * 决议后的时间线形态：常驻一行头部（图标 + 摘要 + 旋转箭头），点击同一行展开/收起
 * 下方详情。历史重放默认折叠；实时决议回写后同走此形态。
 */
function DoneShell({
  tone, icon, text, expanded, onToggle, children,
}: {
  tone: 'ok' | 'reject' | 'skip'
  icon: ReactNode
  text: string
  expanded: boolean
  onToggle: () => void
  children?: ReactNode
}) {
  return (
    <div className={clsx('ic-root', 'ic-done', `ic-done-${tone}`)} data-expanded={expanded || undefined}>
      <button
        type="button"
        className="ic-doneHead"
        aria-expanded={expanded}
        onClick={onToggle}
        title={expanded ? '点击收起' : '点击展开回看'}
      >
        <span className={clsx('ic-collapsed-icon', `ic-collapsed-${tone}`)}>{icon}</span>
        <span className="ic-collapsed-text">{text}</span>
        <ChevronDown className={clsx('ic-doneChevron', expanded && 'ic-doneChevron-up')} />
      </button>
      {expanded && children !== undefined && (
        <div className="ic-doneBody">{children}</div>
      )}
    </div>
  )
}

/** 标签组（审批卡 / 回看详情共用）。 */
function TagList({ tags }: { tags?: string[] }) {
  if (tags === undefined || tags.length === 0) return null
  return (
    <div className="ic-tags">
      {tags.map(t => (
        <span key={t} className="ic-tag" data-danger={DANGER_TAG.test(t) || undefined}>
          {TAG_LABELS[t] ?? t}
        </span>
      ))}
    </div>
  )
}

/* ===========================================================================
 * 时间线侧：等待行 / 决议折叠行 / 结束未处理行
 * ======================================================================== */

/**
 * 等待态的时间线占位行（dsh ask-question-row 的 waiting 语义）：
 * 完整操作卡在底部 composer 接管位，这里只留一行可扫读的活动状态。
 */
export function WaitingInteractionRow({ kind, toolName, question }: {
  kind: 'confirm' | 'ask'
  toolName?: string
  question?: string
}) {
  if (kind === 'confirm') {
    return (
      <div className="ic-wait" title="等待你在下方输入区审批">
        <span className="ic-wait-dot" aria-hidden />
        <span className="ic-wait-label">等待审批</span>
        <span className="ic-wait-sep" aria-hidden>·</span>
        <span className="ic-wait-summary">{truncateSingle(toolName ?? '工具执行', 48)}</span>
      </div>
    )
  }
  return (
    <div className="ic-wait" title="等待你在下方输入区作答">
      <CircleHelp className="ic-wait-icon" />
      <span className="ic-wait-label">AI 提问</span>
      <span className="ic-wait-sep" aria-hidden>·</span>
      <span className="ic-wait-summary">{truncateSingle(question ?? '等待回答', 64)}</span>
    </div>
  )
}

/** 确认交互的时间线视图：仅渲染「已决议（可展开回看）」与「本轮结束未处理（置灰）」。 */
export const ConfirmationCard = memo(function ConfirmationCard({
  toolName, message, tags, input, resolved,
}: ConfirmationCardData) {
  const [expanded, setExpanded] = useState(false)

  const inputText = inputPreview(input)
  const danger = tags?.some(t => DANGER_TAG.test(t))

  // 已决议（历史重放或实时回写）：折叠一行，展开回看。
  if (resolved !== undefined) {
    return (
      <DoneShell
        tone={resolved ? 'ok' : 'reject'}
        icon={resolved ? <CircleCheck /> : <CircleX />}
        text={`${resolved ? '已允许' : '已拒绝'} · ${toolName}`}
        expanded={expanded}
        onToggle={() => setExpanded(v => !v)}
      >
        <p className="ic-message" data-danger={danger || undefined}>{message}</p>
        <TagList tags={tags} />
        {inputText !== '' && <pre className="ic-input">{inputText}</pre>}
        <div className={clsx('ic-result', resolved ? '' : 'ic-result-reject')}>
          {resolved
            ? <><Check /> 已允许，已继续执行</>
            : <><X /> 已拒绝，拒绝结果已返回给 AI</>}
        </div>
      </DoneShell>
    )
  }

  // 本轮已结束（done / error / 用户停止）仍未处理：就地置灰保留，上下文完整但不可再操作。
  return (
    <div className={clsx('ic-root', 'ic-collapsed', 'ic-collapsed-ended')} title="本轮对话已结束，该确认未处理">
      <span className={clsx('ic-collapsed-icon', 'ic-collapsed-skip')}><CircleMinus /></span>
      <span className="ic-collapsed-text">{`未处理 · ${toolName}（本轮已结束）`}</span>
    </div>
  )
})

const FREE_ANSWER_MAX = 500

/** 询问交互的时间线视图：仅渲染「已作答/已跳过（可展开回看）」与「本轮结束未作答（置灰）」。 */
export const AskCard = memo(function AskCard(
  { question, hint, resolvedAnswer }: AskCardData,
) {
  const [expanded, setExpanded] = useState(false)

  if (resolvedAnswer !== undefined) {
    const skipped = resolvedAnswer === null
    const full = skipped ? '' : resolvedAnswer.trim()
    const summary = skipped ? '已跳过提问' : full.replace(/\s+/g, ' ').slice(0, 24)
    return (
      <DoneShell
        tone={skipped ? 'skip' : 'ok'}
        icon={skipped ? <CircleMinus /> : <CircleCheck />}
        text={skipped ? '已跳过提问' : `已回答 · ${summary}`}
        expanded={expanded}
        onToggle={() => setExpanded(v => !v)}
      >
        <p className="ic-message">{question}</p>
        {hint !== undefined && hint !== '' && <p className="ic-hint">{hint}</p>}
        <div className={clsx('ic-result', skipped && 'ic-result-skip')}>
          {skipped
            ? <><CircleMinus /> 已跳过该问题</>
            : <><Check /> 已收到回答</>}
        </div>
        {full !== '' && <div className="ic-done-answer">{full}</div>}
      </DoneShell>
    )
  }

  // 本轮已结束仍未作答：就地置灰保留。
  return (
    <div className={clsx('ic-root', 'ic-collapsed', 'ic-collapsed-ended')} title="本轮对话已结束，该问题未作答">
      <span className={clsx('ic-collapsed-icon', 'ic-collapsed-skip')}><CircleMinus /></span>
      <span className="ic-collapsed-text">未作答（本轮已结束）</span>
    </div>
  )
})

/* ===========================================================================
 * composer 接管侧：底部浮卡（等待态完整操作面，决议后整体离开）
 * ======================================================================== */

/** 审批接管卡：允许一次 → 放行工具；拒绝 → 按错误返回给 LLM。 */
export function ComposerApprovalCard({ data, onSettled }: {
  data: ConfirmationCardData
  onSettled: (approved: boolean) => void
}) {
  const { confirmId, toolName, message, tags, input } = data
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // 会话级授权：勾选后，本会话内完全相同的命令/参数后续免确认（精确匹配，参数不同仍会询问）。
  const [remember, setRemember] = useState(false)

  const act = async (ok: boolean): Promise<void> => {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await resolveConfirmation(confirmId, ok, ok && remember)
      // 成功即交还 composer（父组件把决议写回时间线）；失败留在原卡可重试。
      onSettled(ok)
    } catch (e) {
      setBusy(false)
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const inputText = inputPreview(input)
  const command = commandOf(input)
  const danger = tags?.some(t => DANGER_TAG.test(t))

  return (
    <div className="ic-takeover">
      <div className={clsx('ic-root', 'ic-confirm')} data-approval-key={confirmId}>
        {/* 琥珀色通栏等待条（dsh ApprovalPanel strip） */}
        <div className="ic-ap-strip">
          <span className="ic-ap-dot" />
          等待审批
        </div>

        {/* 理由与命令是无界模型文本，超长时仅该区域滚动，按钮始终可见 */}
        <div className="ic-ap-body" data-approval-scroll tabIndex={0} role="group" aria-label="审批详情">
          <code className="ic-ap-tool">{toolName}</code>
          <div className="ic-ap-headline" data-danger={danger || undefined}>{message}</div>

          <TagList tags={tags} />

          {command !== ''
            ? <div className="ic-ap-command">{command}</div>
            : inputText !== '' && (
              <details className="ic-ap-details">
                <summary>参数预览</summary>
                <pre className="ic-input">{inputText}</pre>
              </details>
            )}
        </div>

        <label className="ic-ap-remember">
          <input
            type="checkbox"
            checked={remember}
            disabled={busy}
            onChange={e => setRemember(e.target.checked)}
          />
          <span>本会话相同命令不再询问（仅限完全相同的参数；拒绝时不生效）</span>
        </label>

        <div className="ic-ap-actions">
          <span className="ic-ap-feedback" role="status">
            {error !== '' ? error : busy ? '正在提交…' : ''}
          </span>
          <button
            type="button"
            className="dsh-btn dsh-btn-outline ic-ap-reject"
            disabled={busy}
            onClick={() => void act(false)}
          >
            拒绝
          </button>
          <button
            type="button"
            className="dsh-btn dsh-btn-primary"
            disabled={busy}
            onClick={() => void act(true)}
          >
            {busy ? '提交中…' : '允许一次'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 询问接管卡：Agent 主动向用户提问，作答/跳过后把文本回给 LLM。 */
export function ComposerQuestionCard({ data, onSettled }: {
  data: AskCardData
  onSettled: (answer: string | null) => void
}) {
  const { askId, question, hint, options } = data
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // 选项：优先后端结构化 options；否则从问题/提示文本兜底解析编号项。
  const choices = (options !== undefined && options.length >= 2
    ? dedupe(options).slice(0, 4)
    : parseNumberedOptions(question, hint))
  const hasChoices = choices.length >= 2

  // 有选项：null = 未选；下标 = 选中的选项；custom = 内联「输入你的答案」行（与选中互斥）。
  const [picked, setPicked] = useState<number | null>(null)
  const [custom, setCustom] = useState('')
  // 无选项时的自由作答框
  const [plainAnswer, setPlainAnswer] = useState('')

  const submit = async (answer: string | null): Promise<void> => {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await resolveAsk(askId, answer)
      onSettled(answer)
    } catch (e) {
      setBusy(false)
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  /** 按当前选择拼出回给 LLM 的作答文本。 */
  const composeAnswer = (): string | null => {
    if (!hasChoices) return plainAnswer.trim() === '' ? null : plainAnswer
    const typed = custom.trim()
    if (typed !== '') return typed
    if (picked !== null) return choices[picked]
    return null
  }

  const canSubmit = hasChoices
    ? picked !== null || custom.trim() !== ''
    : plainAnswer.trim() !== ''

  const submitOnEnter = (event: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>): void => {
    if (event.key !== 'Enter' || event.shiftKey || isComposing(event)) return
    if (!canSubmit || busy) return
    event.preventDefault()
    void submit(composeAnswer())
  }

  return (
    <div className="ic-takeover">
      <div className={clsx('ic-root', 'ic-ask')} data-question-key={askId}>
        {/* 头部：eyebrow 小标签 + 标题 + 右上关闭（= 跳过） */}
        <div className="ic-q-header">
          <div className="ic-q-heading">
            <div className="ic-q-eyebrow">AI 提问</div>
            <h2 className="ic-q-title">{question}</h2>
          </div>
          <button
            type="button"
            className="ic-q-close"
            aria-label="跳过提问"
            title="跳过提问"
            disabled={busy}
            onClick={() => void submit(null)}
          >
            <X />
          </button>
        </div>

        {/* 超长选项列表仅 body 滚动，头部与底部按钮始终可达 */}
        <div className="ic-q-body" data-question-scroll>
          {hint !== undefined && hint !== '' && <div className="ic-q-detail">{hint}</div>}

          {hasChoices ? (
            <div className="ic-options" role="radiogroup">
              {choices.map((opt, i) => (
                <button
                  key={opt}
                  type="button"
                  className={clsx('ic-option', picked === i && 'ic-option-active')}
                  role="radio"
                  aria-checked={picked === i}
                  disabled={busy}
                  onClick={() => { setPicked(i); setCustom('') }}
                >
                  <span className="ic-option-index">{i + 1}</span>
                  <span className="ic-option-copy">
                    <span className="ic-option-label">{opt}</span>
                  </span>
                </button>
              ))}

              {/* 自定义作答行：选项形状 + 无边框内联输入，聚焦或有值时抬升为选中态 */}
              <div className={clsx('ic-custom-row', custom !== '' && 'ic-custom-row-active')}>
                <span className="ic-option-index" aria-hidden="true"><Pencil /></span>
                <input
                  type="text"
                  className="ic-custom-input"
                  value={custom}
                  disabled={busy}
                  maxLength={FREE_ANSWER_MAX}
                  placeholder="输入你的答案"
                  onChange={e => { setCustom(e.target.value); setPicked(null) }}
                  onKeyDown={submitOnEnter}
                />
              </div>
            </div>
          ) : (
            <textarea
              autoFocus
              className="ic-q-textarea"
              value={plainAnswer}
              disabled={busy}
              rows={2}
              maxLength={FREE_ANSWER_MAX}
              placeholder="输入你的答案"
              onChange={e => setPlainAnswer(e.target.value)}
              onKeyDown={submitOnEnter}
            />
          )}
        </div>

        <footer className="ic-q-footer">
          <div className="ic-q-feedback" role="status">
            {error !== '' ? error : busy ? '正在提交…' : ''}
          </div>
          <div className="ic-q-actions">
            <button
              type="button"
              className="dsh-btn dsh-btn-outline"
              disabled={busy}
              onClick={() => void submit(null)}
            >
              跳过
            </button>
            <button
              type="button"
              className="dsh-btn dsh-btn-primary"
              disabled={busy || !canSubmit}
              onClick={() => void submit(composeAnswer())}
            >
              提交
            </button>
          </div>
        </footer>
      </div>
    </div>
  )
}
