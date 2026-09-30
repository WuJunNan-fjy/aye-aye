/**
 * Agent v2 数据层：turn 与 HTTP 连接解耦。
 *
 * - POST /api/v2/agent/turns → 202 { sessionId, turnNo }，agent 在后台运行，
 *   事件经 GET /api/sessions/{id}/events 订阅；启动请求的连接断开不影响 agent。
 * - POST /api/v2/agent/cancel → 幂等显式停止。
 *
 * 载荷与 v1 /agent/chat/stream 同构；错误经 request.ts 拦截器归一化为 Error(message)。
 */

import request, { API_BASE, authHeaders } from '@/api/request'
import type { ChatAttachment } from '@/api/agentApi'
import type { ExecutionMode } from '@/api/agentApi'

export type { ChatAttachment, ExecutionMode }

export interface StartTurnOptions {
  sessionId?: string
  message: string
  /** composite key `{providerId}::{modelId}`；缺省用后端默认模型 */
  modelRef?: string
  hostId?: number
  mode?: ExecutionMode
  attachments?: ChatAttachment[]
  /** 绑定的项目（工作区）ID，部署流程用 */
  workspaceId?: number
  /** 部署模式：true 时后端注入 7 阶段部署流水线提示词 */
  deploy?: boolean
}

export interface StartTurnResult {
  sessionId: string
  turnNo: number
}

export interface CancelTurnResult {
  ok: boolean
  message?: string
}

/**
 * 启动一个后台 turn。同步失败（400 空消息 / 409 同会话互斥 / 422 无 Key / 503 线程池满）
 * 以 reject(Error) 返回，message 可直接展示；调用方不得插入气泡或进入 running 态。
 */
export async function startTurn(options: StartTurnOptions): Promise<StartTurnResult> {
  const { sessionId, message, modelRef, hostId, mode, attachments, workspaceId, deploy } = options
  const body: Record<string, any> = { message }
  if (sessionId) body.sessionId = sessionId
  if (workspaceId != null) body.workspaceId = workspaceId
  if (modelRef) {
    const idx = modelRef.indexOf('::')
    if (idx > 0) {
      body.provider = modelRef.slice(0, idx)
      body.modelId = modelRef.slice(idx + 2)
    } else {
      body.modelId = modelRef
    }
  }
  if (hostId != null) body.hostId = hostId
  if (mode) body.mode = mode
  if (attachments && attachments.length > 0) body.attachments = attachments
  if (deploy === true) body.deploy = true

  const res = await fetch(`${API_BASE}/v2/agent/turns`, {
    method: 'POST',
    headers: await authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const parsed: unknown = await res.json()
      if (typeof parsed === 'object' && parsed !== null && 'message' in parsed) {
        const m = (parsed as { message?: unknown }).message
        if (typeof m === 'string') message = m
      }
    } catch { /* keep raw */ }
    const err = new Error(message)
    ;(err as any).status = res.status
    throw err
  }
  return (await res.json()) as StartTurnResult
}

/** 显式停止：幂等；无运行任务时返回 { ok:false, message }，不视为错误。 */
export async function cancelTurn(sessionId: string): Promise<CancelTurnResult> {
  return (await request.post('/v2/agent/cancel', { sessionId })) as CancelTurnResult
}
