import { memo, useMemo, useState } from 'react'
import clsx from 'clsx'
import {
  BookOpen, SquareTerminal, FileDiff, Search, Pencil, Bot,
  ChevronDown, LoaderCircle,
} from 'lucide-react'
import { HostConnectBlock, detectConnectBlock } from './HostConnectBlock'

/**
 * 工具调用结果卡片 —— 翻译 dsh ui-tool toolviews/{GenericToolCard,ToolRow}。
 *
 * dsh 通过 result 里声明的 `card:'diff'/'read'/'search'/'web'` 等 render intent 选择
 * TerminalBlock/DiffBlock/ReadBlock...(自研 dsh primitives)。Aye SSE wire 只有
 * tool_call{id,toolName,input} + tool_result{id,toolName,output,isError}，无 card 字段，
 * 因此按 **toolName 启发式判定 variant**（skill §铁律：业务逻辑翻译成 Aye 契约），
 * 视觉收敛为通用行 chrome + IN/OUT 卡片，diff 匹配时走简易 diff 高亮。
 *
 * 视觉契约（ToolRow.module.css，token 全部映射到 theme.css）：
 *   - 24px 单行：16px leading + title(14/24) + 2×2 圆点 + FILL 截断 summary
 *   - 展开：overlay IN/OUT 卡片（l1 边框 12px 圆角，markdown-code-block 底色）
 *   - running 态：glare 扫光（data-state=running）+ 行首保持图标
 *   - error 态：行首换红色 state dot，summary/OUT 用错误色
 */

export type ToolStatus = 'running' | 'done' | 'error'

export interface ToolCallRecord {
  /** 与 SSE tool_call/tool_result 的 id 对齐，用于结果回填 */
  id: string
  toolName: string
  input?: unknown
  output?: unknown
  isError?: boolean
  status: ToolStatus
}

type ToolVariant = 'read' | 'bash' | 'diff' | 'search' | 'write' | 'others'

/** toolName 关键词 → variant（Aye 启发式分类，替代 dsh 的 card intent）。 */
function variantFor(toolName: string): ToolVariant {
  const n = toolName.toLowerCase()
  if (n === 'todo_write') return 'others'
  if (/read|cat|view|open/.test(n)) return 'read'
  if (/bash|terminal|command|exec|run|sh$|shell|ps$|npm|git/.test(n)) return 'bash'
  if (/diff|patch/.test(n)) return 'diff'
  if (/search|grep|rg|find|lookup/.test(n)) return 'search'
  if (/write|create|edit|mkdir|mv|cp$|touch/.test(n)) return 'write'
  return 'others'
}

const VARIANT_ICONS: Record<ToolVariant, React.ReactNode> = {
  read: <BookOpen style={{ fontSize: 12 }} />,
  bash: <SquareTerminal style={{ fontSize: 12 }} />,
  diff: <FileDiff style={{ fontSize: 12 }} />,
  search: <Search style={{ fontSize: 12 }} />,
  write: <Pencil style={{ fontSize: 12 }} />,
  others: <Bot style={{ fontSize: 12 }} />,
}

/**
 * 工具友好名（dsh 显示 "Pwsh" 这类人可读短名，而非 raw toolName）。
 * 命中关键词优先；否则把 snake_case 工具名首段大写作为兜底。
 */
function friendlyName(toolName: string, variant: ToolVariant): string {
  const n = toolName.toLowerCase()
  // 精确工具名特例：必须先于关键词正则（todo_write 含 write）。
  if (n === 'todo_write') return '待办'
  if (n === 'run_ssh_async') return '异步SSH'
  if (n === 'ssh_job_poll') return '作业轮询'
  if (/bash|terminal|command|exec|run_command|pwsh|powershell|\bsh$|shell/.test(n)) return 'Pwsh'
  if (/read|cat|view|list_dir|ls$/.test(n)) return 'Read'
  if (/write|create|touch/.test(n)) return 'Write'
  if (/edit|patch|replace/.test(n)) return 'Edit'
  if (/diff/.test(n)) return 'Diff'
  if (/search|grep|rg$|find|lookup/.test(n)) return 'Search'
  if (/list_host|host/.test(n)) return 'Hosts'
  if (/^ssh/.test(n)) return 'SSH'
  if (/sftp/.test(n)) return 'SFTP'
  if (variant === 'bash') return 'Pwsh'
  // 兜底：取首段、首字母大写（read_file → Read）。
  const head = toolName.split(/[_\-.]/)[0] ?? toolName
  return head.charAt(0).toUpperCase() + head.slice(1)
}

/** 单行截断，避免把整段输出灌进摘要。 */
function truncate(s: string, max = 72): string {
  const one = s.replace(/\s+/g, ' ').trim()
  return one.length > max ? `${one.slice(0, max - 1)}…` : one
}

/** 像一个远程绝对路径：以 / 开头、无空白/换行、长度合理（拒绝把整段命令输出误判成路径）。 */
function looksLikePath(s: string): boolean {
  return s.startsWith('/') && s.length <= 4096 && !/\s/.test(s)
}

/** 从 input / output 里尽力抽出一个文件路径（read/write/edit 卡片展示为可打开链接）。 */
function extractPath(input: unknown, output: unknown): string | undefined {
  const scan = (v: unknown): string | undefined => {
    if (typeof v === 'string') return looksLikePath(v) ? v : undefined
    if (v && typeof v === 'object') {
      const o = v as Record<string, unknown>
      for (const k of ['path', 'file', 'src', 'dst', 'filename', 'target']) {
        const p = o[k]
        if (typeof p === 'string' && looksLikePath(p)) return p
      }
    }
    return undefined
  }
  return scan(input) ?? scan(output)
}

/** 把任意 input/output 规整为可读文本。 */
function toText(v: unknown): string {
  if (v === undefined || v === null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (Array.isArray(v)) {
    return v.map(item => toText(item)).join('\n')
  }
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>
    // 常见 bash 输出：{ stdout, stderr }
    const stdout = o.stdout
    const stderr = o.stderr
    if (typeof stdout === 'string' && typeof stderr === 'string') {
      return stdout + (stderr.length > 0 ? `\n${stderr}` : '')
    }
    if (typeof stdout === 'string') {
      return stderr !== undefined && String(stderr).length > 0 ? `${stdout}\n${stderr}` : stdout
    }
    if (typeof stderr === 'string') return stderr
    // 常见 read 输出：{ lines: string[], totalLines, path }
    if (Array.isArray(o.lines)) return o.lines.map(l => toText(l)).join('\n')
    try {
      return JSON.stringify(v, null, 2)
    } catch {
      return String(v)
    }
  }
  return String(v)
}

/** 从 input 对象里取一个字符串字段（command/query/pattern/path 等）。 */
function inputField(input: unknown, keys: string[]): string | undefined {
  if (input && typeof input === 'object') {
    const o = input as Record<string, unknown>
    for (const k of keys) {
      const v = o[k]
      if (typeof v === 'string' && v.trim().length > 0) return v.trim()
    }
  }
  return undefined
}

/**
 * 单行摘要：优先描述"要做什么"（输入动作：命令/路径/查询），
 * 而不是把整段输出灌进来；全部做单行截断（dsh "List top-level folders…" 风格）。
 */
function makeSummary(record: ToolCallRecord, variant: ToolVariant): string {
  if (record.isError) {
    const err = toText(record.output).split('\n')[0] || '执行失败'
    return truncate(err)
  }
  // 待办工具：用动作 + 完成数做摘要（TodoDock 码头另有结构化渲染）。
  if (record.toolName === 'todo_write') {
    const o = (record.input ?? {}) as Record<string, unknown>
    const action = String(o.action ?? '')
    if (action === 'clear') return '清空待办'
    if (action === 'list') return '查看待办'
    if (action === 'replace' && Array.isArray(o.items)) {
      const items = o.items as Array<Record<string, unknown>>
      const d = items.filter(i => i.status === 'completed').length
      return `更新进度 ${d}/${items.length}`
    }
  }
  // bash/命令类：显示要执行的命令本身。
  if (variant === 'bash') {
    const cmd = inputField(record.input, ['command', 'cmd', 'script', 'shell'])
    if (cmd !== undefined) return truncate(cmd)
  }
  // 搜索类：显示查询词。
  if (variant === 'search') {
    const q = inputField(record.input, ['query', 'pattern', 'keyword', 'search'])
    if (q !== undefined) return truncate(q)
  }
  // 文件类：显示目标路径（可点击链接在渲染层处理）。
  const p = extractPath(record.input, undefined)
  if (p !== undefined) return truncate(p, 96)
  // 异步作业轮询：摘要直接显示 job_id（比原始 JSON 可读）。
  const jobId = inputField(record.input, ['job_id'])
  if (jobId !== undefined) return truncate(jobId, 48)
  // 兜底：输入首行 → 输出首行。空对象/空数组（{}、[]）视为无意义输入，跳过；
  // 无任何可摘要信息时返回空串，渲染层只显示工具名（不再重复展示工具名或空 {}）。
  const isEmptyStructure = (s: string) => s === '' || s === '{}' || s === '[]' || s === 'null'
  const inp = toText(record.input).trim()
  if (!isEmptyStructure(inp)) return truncate(inp.split('\n')[0] ?? inp)
  const out = toText(record.output).trim()
  if (!isEmptyStructure(out)) return truncate(out.split('\n')[0] ?? out)
  return ''
}

/** 简单 diff 高亮：仅对 `+`/`-` 前缀行着色，其余原样。 */
function DiffBody({ text }: { text: string }) {
  return (
    <pre className="tc-diff">
      {text.split('\n').map((line, i) => (
        <span key={i} className={line.startsWith('+') ? 'tc-diffAdd' : line.startsWith('-') ? 'tc-diffDel' : undefined}>
          {line === '' ? '\u00a0' : line}
          {i < text.split('\n').length - 1 ? '\n' : null}
        </span>
      ))}
    </pre>
  )
}

/** 单行折叠体（含展开态 IN 卡片）。用于 dsh TerminalBlock 的 command 模式：task→expanded、result→折叠 summary。 */
export const ToolCard = memo(function ToolCard({
  record,
  openPath,
  showHostBlock = false,
  canResumeTurn = true,
  onResumeTurn,
}: {
  record: ToolCallRecord
  openPath?: (path: string) => void
  /** 渲染主对话线连接阻断卡（同一轮仅首个命中工具渲染，由 AssistantMessage 去重后传入）。 */
  showHostBlock?: boolean
  /** AI 是否空闲：运行中阻断卡的「继续执行」按钮禁用。 */
  canResumeTurn?: boolean
  /** 连接成功后续跑：由 ConversationPage 注入"已连接，请继续…"发送动作。 */
  onResumeTurn?: () => void
}) {
  const [expanded, setExpanded] = useState(false)
  const variant = variantFor(record.toolName)
  const inputText = toText(record.input)
  const outputText = toText(record.output)
  const hasInput = inputText.length > 0
  const hasOutput = outputText.length > 0
  const isDiff = variant === 'diff' || outputText.split('\n').filter(l => l.startsWith('+') || l.startsWith('-')).length >= 3
  const filePath = extractPath(record.input, record.output)

  // dsh FileLink：read/write/edit 工具的行摘要渲染为可点击路径链接（click 不触发行折叠）。
  const isFileLink = filePath !== undefined && openPath !== undefined && !record.isError
  const openFile = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation()
    if (filePath !== undefined && openPath !== undefined) openPath(filePath)
  }

  const state = record.status
  const expandable = hasInput || hasOutput
  // diff 命中（或 output 明显是 +/- 行）→ 用 DiffBody 替代普通 OUT 文本，避免重复。
  const useDiff = isDiff && hasOutput && !record.isError
  const showOutText = hasOutput && !useDiff
  const displayName = friendlyName(record.toolName, variant)
  const summary = makeSummary(record, variant)
  // 连接类阻断：错误工具卡下方追加醒目的主对话线引导卡（同一轮去重由上层控制）。
  const hostBlockKind = showHostBlock ? detectConnectBlock(record) : null

  return (
    <>
    <div
      className={clsx('tc-root', expanded && 'tc-rootOpen')}
      data-variant={variant}
      data-tool={record.toolName}
      data-state={state}
    >
      {/* 无障碍运行态文本 */}
      {state !== 'done' && (
        <span className="tc-visuallyHidden">
          {state === 'running' ? '正在运行' : '失败'}
        </span>
      )}

      {/* 行首：state dot / spinner / 图标 交互 */}
      <div className="tc-row" role="button" tabIndex={0} aria-expanded={expanded}
        onClick={() => expandable && setExpanded(v => !v)}
        onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && expandable) { e.preventDefault(); setExpanded(v => !v) } }}
      >
        <span className="tc-leading">
          {state === 'error'
            ? <span className="tc-stateDot" aria-hidden />
            : state === 'running'
              ? variant === 'bash'
                ? (
                  <span className="tm-fx tm-ico-py" aria-hidden>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                      <rect width="18" height="18" x="3" y="3" rx="2" />
                      <path className="i-chev" d="m7 11 2-2-2-2" />
                      <path className="i-cur" d="M11 13h4" />
                    </svg>
                  </span>
                )
                : <LoaderCircle className="tc-runIcon" aria-hidden style={{ fontSize: 12 }} />
              : VARIANT_ICONS[variant]}
        </span>
        <span className="tc-title">{displayName}</span>
        <span className="tc-sep" aria-hidden />
        {summary !== '' && (
          isFileLink
            ? <button type="button" className="tc-fileLink" onClick={openFile}>{summary}</button>
            : <span className={clsx('tc-summary', record.isError && 'tc-errorSummary')}>{summary}</span>
        )}
        {expandable && (
          <span className="tc-chevron" aria-hidden>
            <ChevronDown style={{ fontSize: 11 }} />
          </span>
        )}
      </div>

      {/* 展开体：IN/OUT 卡片 —— dsh ioSection 只在流程里渲染 In/Out，末位工具若有 output 则 OUT。 */}
      {expanded && (
        <div className="tc-bodyWrap">
          {hasInput && (
            <div className="tc-ioCard">
              <div className="tc-ioSection">
                <span className="tc-ioLabel">IN</span>
                <span className="tc-ioText">{inputText}</span>
              </div>
              {showOutText && <span className="tc-ioDivider" aria-hidden />}
              {showOutText && (
                <div className="tc-ioSection">
                  <span className="tc-ioLabel">OUT</span>
                  <span className="tc-ioText" data-error={record.isError || undefined}>
                    {outputText}
                  </span>
                </div>
              )}
            </div>
          )}
          {!hasInput && showOutText && (
            <div className="tc-ioCard">
              <div className="tc-ioSection">
                <span className="tc-ioLabel">OUT</span>
                <span className="tc-ioText" data-error={record.isError || undefined}>
                  {outputText}
                </span>
              </div>
            </div>
          )}
          {useDiff && (
            <div className="tc-diffBodyWrap">
              <DiffBody text={outputText} />
            </div>
          )}
        </div>
      )}
    </div>
      {hostBlockKind !== null && (
        <HostConnectBlock
          kind={hostBlockKind}
          record={record}
          canResume={canResumeTurn}
          onResume={onResumeTurn}
        />
      )}
    </>
  )
})