import { memo, useEffect, useState } from 'react'
import { Check, ChevronDown, ChevronUp, CircleDashed, Loader2, Minus, X } from 'lucide-react'
import type { ToolCallRecord } from '@/components/chat/ToolCard'
import { DeployRocketIcon } from './DeployRocketIcon'
import './DeployDock.css'

/**
 * 部署进度码头：与 TodoDock 同座（composer 顶部、736 居中列），
 * 数据零后端改动——回放全会话可见消息里 deploy_stage 工具调用的结构化输入，
 * 每个阶段取最后一次状态（后端允许失败修复后重新声明 running）。
 * 阶段固定 7 个，与后端 DeployStage 枚举一一对应。
 */

export type DeployStageStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped'

export interface DeployStageEntry {
  id: string
  title: string
  status: DeployStageStatus
  summary?: string
}

const STAGES: ReadonlyArray<{ id: string; title: string }> = [
  { id: 'select', title: '选择项目' },
  { id: 'detect', title: '看懂项目' },
  { id: 'prepare', title: '准备环境' },
  { id: 'build', title: '构建打包' },
  { id: 'confirm', title: '确认产物' },
  { id: 'upload', title: '上传服务器' },
  { id: 'verify', title: '启动与验证' },
]

type StageMap = Map<string, { status: Exclude<DeployStageStatus, 'pending'>; summary?: string }>

/** 顺序回放 deploy_stage 调用，每阶段保留最后一次声明。 */
function reduceDeploy(tools: ToolCallRecord[] | undefined, acc: StageMap): StageMap {
  if (!tools) return acc
  for (const t of tools) {
    if (t.toolName !== 'deploy_stage') continue
    const input = (t.input ?? {}) as Record<string, unknown>
    const id = typeof input.stage === 'string' ? input.stage : ''
    const status = typeof input.status === 'string' ? input.status : ''
    if (!STAGES.some(s => s.id === id)) continue
    if (status !== 'running' && status !== 'done' && status !== 'failed' && status !== 'skipped') continue
    const summary = typeof input.summary === 'string' && input.summary.trim()
      ? input.summary.trim() : undefined
    acc.set(id, { status, summary })
  }
  return acc
}

/** 跨全部可见消息求部署阶段快照；从未声明过阶段返回 null（卡片不渲染）。 */
export function standingDeployFromMessages(
  messages: readonly { tools?: ToolCallRecord[] }[],
): DeployStageEntry[] | null {
  const map: StageMap = new Map()
  for (const m of messages) reduceDeploy(m.tools, map)
  if (map.size === 0) return null
  return STAGES.map(s => {
    const rec = map.get(s.id)
    return rec
      ? { id: s.id, title: s.title, status: rec.status, summary: rec.summary }
      : { id: s.id, title: s.title, status: 'pending' }
  })
}

/** verify 声明 done 视为整轮部署完成（失败不修，允许在模式内重试，不自动退出）。 */
export function deployIsFinished(stages: readonly DeployStageEntry[] | null): boolean {
  return stages?.[6]?.status === 'done'
}

function StageGlyph({ status }: { status: DeployStageStatus }) {
  switch (status) {
    case 'done':
      return (
        <span className="dp-glyph dp-glyphDone" aria-hidden>
          <Check size={11} strokeWidth={2.6} />
        </span>
      )
    case 'running':
      return <Loader2 size={13} strokeWidth={2} className="dp-glyph dp-glyphRunning" aria-hidden />
    case 'failed':
      return (
        <span className="dp-glyph dp-glyphFailed" aria-hidden>
          <X size={11} strokeWidth={2.6} />
        </span>
      )
    case 'skipped':
      return <Minus size={13} strokeWidth={2} className="dp-glyph dp-glyphSkipped" aria-hidden />
    case 'pending':
    default:
      return <CircleDashed size={13} strokeWidth={1.6} className="dp-glyph dp-glyphPending" aria-hidden />
  }
}

function headerLabel(stages: readonly DeployStageEntry[]): string {
  const done = stages.filter(s => s.status === 'done' || s.status === 'skipped').length
  const failed = [...stages].reverse().find(s => s.status === 'failed')
  const running = stages.find(s => s.status === 'running')
  if (stages[6]?.status === 'done') return `已完成 ${done}/7`
  if (failed) return `部署失败 · ${failed.title}（可修复后继续）`
  if (running) return `${done}/7 · ${running.title}进行中`
  return `${done}/7`
}

interface Props {
  stages: DeployStageEntry[] | null
}

export const DeployDock = memo(function DeployDock({ stages }: Props) {
  const [collapsed, setCollapsed] = useState(true)

  const hasFailure = stages?.some(s => s.status === 'failed') ?? false
  const finished = deployIsFinished(stages)
  const hasRunning = stages?.some(s => s.status === 'running') ?? false

  // 失败自动展开让用户看到卡点；全部完成自动收口为极简摘要。
  useEffect(() => {
    if (hasFailure) setCollapsed(false)
    else if (finished) setCollapsed(true)
  }, [hasFailure, finished])

  if (!stages) return null

  return (
    <section
      className="dp-root"
      data-finished={finished || undefined}
      data-failed={hasFailure || undefined}
      aria-label="部署进度"
    >
      <div className="dp-body">
        <button
          type="button"
          className="dp-header"
          aria-expanded={!collapsed}
          onClick={() => setCollapsed(v => !v)}
        >
          <span className={`dp-lead${hasRunning ? ' is-deploying' : ''}`} aria-hidden><DeployRocketIcon size={14} /></span>
          <span className="dp-title">部署进度</span>
          <span className="dp-progress">{headerLabel(stages)}</span>
          <span className="dp-chevron" aria-hidden>
            {collapsed ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          </span>
        </button>
        {!collapsed && (
          <ul className="dp-list">
            {stages.map(s => (
              <li key={s.id} className="dp-item" data-status={s.status}>
                <span className="dp-glyphCell"><StageGlyph status={s.status} /></span>
                <span className="dp-itemText">
                  <span className="dp-itemTitle">{s.title}</span>
                  {s.summary && <span className="dp-itemSummary">{s.summary}</span>}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
})
