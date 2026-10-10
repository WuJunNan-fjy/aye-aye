/**
 * Agent 对话 SSE 数据层（直连 Spring Boot sidecar，8080 端口）。
 *
 * wire 契约对应后端 AgentEventJson.java（event: message + data: {...}，type 用 snake_case）：
 *   start → (context_injected | thinking | text | tool_call | tool_result)* → (error | done)
 *
 * 注意：SSE 用 fetch + ReadableStream 消费（不经过 axios），baseURL 取
 * request.ts 的 API_BASE（跟随页面 hostname，局域网访问自动指向服务端 IP）。
 */

import request, { API_BASE, authHeaders } from '@/api/request'

export type ExecutionMode = 'readonly' | 'write_confirm' | 'all_confirm'

export type AgentStreamEvent =
  | { type: 'start'; sessionId: string }
  | { type: 'context_injected'; name: string; summary: string }
  | { type: 'thinking'; content: string }
  | { type: 'text'; content: string }
  | { type: 'tool_call'; id: string; toolName: string; input?: Record<string, any> }
  | { type: 'tool_result'; id: string; toolName: string; output?: any; isError?: boolean }
  | { type: 'error'; message: string }
  | { type: 'done' }
  | { type: 'confirmation_request'; confirmId: string; toolName: string; message: string; tags?: string[]; input?: Record<string, any> }
  | { type: 'ask_user'; askId: string; question: string; hint?: string; options?: string[] }

/** 每次调用读取最新的 API_BASE（桌面端 ensureBackend 会更新端口）。 */
const getBaseUrl = () => API_BASE

/** 把 fetch/网络错误整理成可展示的中文消息。 */
export function agentErrorMessage(err: unknown): string {
  if (err instanceof Error) {
    if (err.name === 'AbortError') return '已停止生成'
    if (err.message) return err.message
  }
  return '无法连接后端（:8080），请确认 Aye 后端已启动'
}

/**
 * 读取 SSE 流，产出解析后的事件对象。
 * 兼容后端 `event: message` + `data: {...}`，只消费 `data:` 行并忽略 `[DONE]` 哨兵。
 * 支持 `data:` 多行（斜杠 `\n` 续行）与历史响应里可能的换行 JSON。
 */
export async function* readSSE(
  response: Response,
): AsyncGenerator<Record<string, any>> {
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (line.startsWith('data:')) {
        const dataLine = line.slice(5).trimStart()
        // 兼容多行 data（还有个 `data:` 前缀的续行可能带着原始 data: 开头）
        const raw = dataLine.replace(/^data:\s*/, '').trim()
        if (raw === '' || raw === '[DONE]') continue
        try {
          yield JSON.parse(raw)
        } catch (err) {
          // 坏 JSON 行：不再静默丢弃，打告警便于联调/排障（不影响正常流式消费）
          console.warn('[SSE] 忽略无法解析的 data 行:', raw, err)
        }
      }
    }
  }
}

export interface StreamAgentChatOptions {
  sessionId?: string
  message: string
  /** composite key `{providerId}::{modelId}`；缺省用后端默认模型 */
  modelRef?: string
  hostId?: number
  mode?: ExecutionMode
  /** 用户拖入的附件引用（桌面端为本机路径） */
  attachments?: ChatAttachment[]
  /** 外部引用，用于存 AbortController 以便交互式停止 */
  controllerRef?: { current: AbortController | null }
}

/**
 * 随对话提交的附件引用（后端 ChatAttachment）。
 * 桌面端 path=用户本机绝对路径（零上传）；浏览器端 path=uploads 落盘路径。
 */
export interface ChatAttachment {
  name: string
  path: string
  /** 目录引用（桌面端）；缺省按 false 处理 */
  isDirectory?: boolean
  /** 桌面端主进程颁发的路径来源凭证（本机路径必填，uploads 路径可空） */
  pathToken?: string
}

/** 上传成功后由后端返回的附件元数据。 */
export interface UploadedAttachment {
  id: string
  name: string
  size: number
  mimetype: string
  path: string
}

/**
 * 上传本地文件到后端（拖拽进对话的精髓）。
 * 返回带服务端绝对路径的元数据；随后在 streamAgentChat 的 attachments 里带上。
 */
export async function uploadAttachment(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<UploadedAttachment> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await request.post('/agent/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 0, // 大文件上传不限制超时
    onUploadProgress: (e) => {
      if (e.lengthComputable && onProgress && e.total && e.total > 0) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    },
  })
  const body = res as { success?: boolean; file?: UploadedAttachment }
  if (body?.success && body.file) return body.file
  throw new Error('上传失败：后端未返回附件信息')
}

/**
 * 人工审批回传：前端批准/拒绝一次工具执行请求。
 * 对应的挂起确认（awaitSessionConfirm）收到结果后被阻塞的 Agent 线程继续主循环。
 */
export async function resolveConfirmation(
  confirmId: string, approved: boolean, remember = false,
): Promise<void> {
  const res = await request.post('/agent/confirm', { confirmId, approved, remember })
  const body = res as { ok?: boolean; message?: string }
  if (body?.ok !== true) {
    throw new Error(body?.message || '确认请求不存在或已过期')
  }
}

/**
 * ask_user 主动询问回传：前端作答 Agent 提出的问题。
 * 对应的挂起询问（awaitSessionAsk）收到作答后被阻塞的 Agent 线程继续主循环。
 * answer 为空串或 null 视为取消/跳过。
 */
export async function resolveAsk(askId: string, answer: string | null): Promise<void> {
  const res = await request.post('/agent/ask', { askId, answer: answer ?? '' })
  const body = res as { ok?: boolean; message?: string }
  if (body?.ok !== true) {
    throw new Error(body?.message || '询问请求不存在或已过期')
  }
}
export async function* streamAgentChat(
  options: StreamAgentChatOptions,
): AsyncGenerator<AgentStreamEvent> {
  const { sessionId, message, modelRef, hostId, mode, attachments, controllerRef } = options

  const body: Record<string, any> = { message }
  if (sessionId) body.sessionId = sessionId
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

  const controller = new AbortController()
  if (controllerRef) controllerRef.current = controller
  const timeoutId = setTimeout(() => controller.abort(), 300000)

  try {
    const res = await fetch(`${getBaseUrl()}/agent/chat/stream`, {
      method: 'POST',
      headers: await authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
      signal: controller.signal,
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
      throw new Error(message)
    }

    const generator = readSSE(res)
    for await (const evt of generator) {
      // 归一化为判别联合（未知 type 直接跳过）
      const type = evt?.type
      if (type === 'start') { yield { type: 'start', sessionId: String(evt.sessionId ?? '') } }
      else if (type === 'context_injected') {
        yield {
          type: 'context_injected',
          name: String(evt.name ?? ''),
          summary: String(evt.summary ?? ''),
        }
      }
      else if (type === 'thinking') { yield { type: 'thinking', content: String(evt.content ?? '') } }
      else if (type === 'text') { yield { type: 'text', content: String(evt.content ?? '') } }
      else if (type === 'tool_call') { yield { type: 'tool_call', id: String(evt.id ?? ''), toolName: String(evt.toolName ?? ''), input: evt.input } }
      else if (type === 'tool_result') { yield { type: 'tool_result', id: String(evt.id ?? ''), toolName: String(evt.toolName ?? ''), output: evt.output, isError: evt.isError === true } }
      else if (type === 'error') { yield { type: 'error', message: String(evt.message ?? '') } }
      else if (type === 'done') { yield { type: 'done' } }
      else if (type === 'confirmation_request') {
        yield {
          type: 'confirmation_request',
          confirmId: String(evt.confirmId ?? ''),
          toolName: String(evt.toolName ?? ''),
          message: String(evt.message ?? ''),
          tags: Array.isArray(evt.tags) ? evt.tags.map(String) : undefined,
          input: evt.input,
        }
      }
      else if (type === 'ask_user') {
        yield {
          type: 'ask_user',
          askId: String(evt.askId ?? ''),
          question: String(evt.question ?? ''),
          hint: evt.hint != null ? String(evt.hint) : undefined,
          options: Array.isArray(evt.options)
            ? evt.options.map((o: unknown) => String(o)).filter((s: string) => s.trim() !== '').slice(0, 4)
            : undefined,
        }
      }
    }
  } catch (err) {
    if (err instanceof Error && err.name === 'AbortError') {
      // 用户主动停止：不抛，让调用方以取消收尾；通过返回标记 end 处理。
      yield { type: 'error', message: '已停止生成' }
    } else {
      throw err
    }
  } finally {
    clearTimeout(timeoutId)
    if (controllerRef) controllerRef.current = null
  }
}