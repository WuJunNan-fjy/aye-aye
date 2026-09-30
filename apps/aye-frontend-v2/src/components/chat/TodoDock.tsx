import { memo, useEffect, useId, useState } from 'react'
import { ChevronDown, ChevronUp, ListChecks, Minus } from 'lucide-react'
import type { ToolCallRecord } from './ToolCard'
import './TodoDock.css'

/**
 * 待办码头（1:1 复刻 dsh ui-conversation TodoPanel）：常驻 composer 座顶部的
 * 「当前计划」横条。整条会话只保留<b>一个</b>实例，取代此前每条助手消息都渲染的
 * TodoProgress——计划怎么滚都钉在输入框上方。
 *
 * 数据源：零后端改动，回放全会话可见消息里 todo_write 工具调用的结构化输入，
 * 最后一次 replace 为当前快照，clear 清空（与后端 TodoStore 语义一致）。
 * 默认折叠为 36px 摘要行；状态 glyph 用 dsh 同款 14×14 SVG（对勾环/渐变旋转环/虚线环）。
 */

export interface TodoItem {
  content: string
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled'
}

const STATUSES: readonly TodoItem['status'][] = [
  'pending', 'in_progress', 'completed', 'cancelled',
]

/** 按时间顺序回放单条消息内的 todo_write：最后一次 replace 即快照，clear 清空。 */
function reduceCalls(tools: ToolCallRecord[] | undefined, snapshot: TodoItem[] | null): TodoItem[] | null {
  if (!tools) return snapshot
  let next = snapshot
  for (const t of tools) {
    if (t.toolName !== 'todo_write') continue
    const input = (t.input ?? {}) as Record<string, unknown>
    const action = typeof input.action === 'string' ? input.action : ''
    if (action === 'clear') {
      next = null
    } else if (action === 'replace' && Array.isArray(input.items)) {
      const items: TodoItem[] = []
      for (const raw of input.items) {
        const o = raw as Record<string, unknown>
        const content = typeof o?.content === 'string' ? o.content : ''
        if (!content.trim()) continue
        const status = STATUSES.includes(String(o.status) as TodoItem['status'])
          ? String(o.status) as TodoItem['status']
          : 'pending'
        items.push({ content, status })
      }
      if (items.length > 0) next = items
    }
  }
  return next
}

/** 跨全部可见消息求「当前计划」快照；无计划返回 null（卡片不渲染）。 */
export function standingTodoFromMessages(
  messages: readonly { tools?: ToolCallRecord[] }[],
): TodoItem[] | null {
  let snapshot: TodoItem[] | null = null
  for (const m of messages) snapshot = reduceCalls(m.tools, snapshot)
  return snapshot
}

/** 完成：实色描边环 + 对勾（成功色）。 */
function CompletedGlyph() {
  return (
    <svg width={14} height={14} viewBox="0 0 14 14" fill="none" aria-hidden className="td-glyph td-glyphCompleted">
      <circle cx="7" cy="7" r="6.4" stroke="currentColor" strokeWidth="1.2" />
      <path
        d="M10.9631 5.71411L7.70154 8.97571C7.48011 9.19714 7.27736 9.40099 7.09229 9.54993C6.89742 9.70669 6.66314 9.85279 6.3634 9.90027C6.2049 9.92534 6.04339 9.92534 5.88489 9.90027C5.58515 9.85279 5.35087 9.70669 5.15601 9.54993C4.97093 9.40099 4.76818 9.19714 4.54675 8.97571L3.03516 7.46411L3.96313 6.53613L5.47473 8.04773C5.7169 8.28989 5.86196 8.43389 5.97888 8.52795C6.08597 8.61409 6.10875 8.60701 6.08997 8.604C6.11259 8.60758 6.13571 8.60758 6.15833 8.604C6.13954 8.60701 6.16232 8.61409 6.26941 8.52795C6.38633 8.43389 6.53139 8.28989 6.77356 8.04773L10.0352 4.78613L10.9631 5.71411Z"
        fill="currentColor"
      />
    </svg>
  )
}

/** 进行中：业务蓝渐变环，CSS 驱动旋转。 */
function ProgressGlyph() {
  const gradientId = useId()
  return (
    <svg width={14} height={14} viewBox="0 0 14 14" fill="none" aria-hidden className="td-glyph td-glyphProgress">
      <defs>
        <linearGradient id={gradientId} x1="2.5" y1="12" x2="10.5" y2="3.5" gradientUnits="userSpaceOnUse">
          <stop stopColor="currentColor" />
          <stop offset="1" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <circle cx="7" cy="7" r="6.4" stroke={`url(#${gradientId})`} strokeWidth="1.2" />
    </svg>
  )
}

/** 待办：虚线未开始环（dash 2.4 2.4）。 */
function PendingGlyph() {
  return (
    <svg width={14} height={14} viewBox="0 0 14 14" fill="none" aria-hidden className="td-glyph td-glyphPending">
      <circle cx="7" cy="7" r="6.4" stroke="currentColor" strokeWidth="1.2" strokeDasharray="2.4 2.4" />
    </svg>
  )
}

/** 取消：弱化减号（dsh 无此状态，Aye 后端支持，沿用 16×16 glyph 座）。 */
function CancelledGlyph() {
  return (
    <span className="td-glyph td-glyphCancelled" aria-hidden>
      <Minus width={13} height={13} strokeWidth={1.6} />
    </span>
  )
}

function StatusGlyph({ status }: { status: TodoItem['status'] }) {
  switch (status) {
    case 'completed': return <CompletedGlyph />
    case 'in_progress': return <ProgressGlyph />
    case 'cancelled': return <CancelledGlyph />
    case 'pending': return <PendingGlyph />
    default: return null
  }
}

/** 头部摘要：各状态计数以「·」连接，零计数段省略（非空列表至少保留一段）。 */
function progressLabel(items: readonly TodoItem[]): string {
  const done = items.filter(i => i.status === 'completed').length
  const active = items.filter(i => i.status === 'in_progress').length
  const cancelled = items.filter(i => i.status === 'cancelled').length
  const pending = items.length - done - active - cancelled
  return [
    ...(done > 0 ? [`已完成 ${done}`] : []),
    ...(active > 0 ? [`进行中 ${active}`] : []),
    ...(pending > 0 ? [`待办 ${pending}`] : []),
    ...(cancelled > 0 ? [`已取消 ${cancelled}`] : []),
  ].join(' · ')
}

interface Props {
  /** 当前计划快照（null/空不渲染）；由 ConversationPage 跨全会话消息求得。 */
  items: TodoItem[] | null
}

export const TodoDock = memo(function TodoDock({ items }: Props) {
  // dsh 契约：默认折叠，开合仅由用户控制（切会话靠 key 重置实例）。
  const [collapsed, setCollapsed] = useState(true)

  // 计划进入终态（无待办/进行中）时自动收口为极简摘要行；用户仍可点开回看。
  // 历史重放时默认就是折叠态，不会出现「先展开再收起」的闪动。
  const allDone = items !== null
    && items.length > 0
    && items.every(i => i.status === 'completed' || i.status === 'cancelled')
  useEffect(() => {
    if (allDone) setCollapsed(true)
  }, [allDone])

  if (!items || items.length === 0) return null

  return (
    <section className="td-root" data-done={allDone || undefined} aria-label="任务计划">
      <div className="td-body">
        <button
          type="button"
          className="td-header"
          aria-expanded={!collapsed}
          onClick={() => setCollapsed(v => !v)}
        >
          <span className="td-lead" aria-hidden><ListChecks size={14} /></span>
          <span className="td-title">任务计划</span>
          <span className="td-progress">{progressLabel(items)}</span>
          <span className="td-chevron" aria-hidden>
            {collapsed ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          </span>
        </button>
        {!collapsed && (
          <ul className="td-list">
            {items.map(item => (
              <li key={item.content} className="td-item" data-status={item.status}>
                <span className="td-glyphCell" aria-hidden><StatusGlyph status={item.status} /></span>
                <span className="td-content">{item.content}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
})
