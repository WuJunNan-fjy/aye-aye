import request from '@/api/request'

/** 会话元信息（GET /api/sessions 返回的列表项）。 */
export interface SessionInfo {
  id: string
  title: string
  hostId?: number | null
  workspaceId?: number | null
  workspaceTitle?: string | null
  deploy?: boolean
  createdAt: number
  updatedAt: number
  /** 当前运行中的 turn 序号（session_turns 投影，附加可空字段；冷恢复闭合后消失）。 */
  runningTurn?: number | null
}

/** 单条消息 part 的 wire 格式（与后端 MessageCodec 对齐）。 */
export type SessionMessagePart =
  | { type: 'text'; text: string }
  | { type: 'reasoning'; text: string }
  | { type: 'tool_call'; id: string; toolName: string; input?: Record<string, any> }
  | { type: 'tool_result'; id: string; output?: unknown; isError?: boolean }
  | { type: 'context'; name: string; summary: string }
  | {
      type: 'interaction_confirm'
      id: string
      toolName: string
      message: string
      tags?: string[]
      input?: Record<string, any>
      approved: boolean
    }
  | {
      type: 'interaction_ask'
      id: string
      question: string
      hint?: string | null
      options?: string[]
      answer?: string | null
      skipped?: boolean
    }

/** 一条历史消息（GET /api/sessions/{id}/messages 返回项）。 */
export interface SessionMessage {
  role: 'user' | 'assistant' | 'tool' | 'system'
  parts: SessionMessagePart[]
}

export default {
  list: () =>
    request.get('/sessions') as Promise<{ sessions: SessionInfo[] }>,

  messages: (id: string) =>
    request.get(`/sessions/${id}/messages`) as Promise<{ messages: SessionMessage[] }>,

  delete: (id: string) =>
    request.delete(`/sessions/${id}`) as Promise<{ success: boolean }>,
}
