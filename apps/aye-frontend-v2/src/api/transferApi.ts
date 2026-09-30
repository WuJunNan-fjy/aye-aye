import axios from 'axios'
import { API_BASE } from '@/api/request'

export const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB，与后端一致
const MAX_WORKERS = 3
const MAX_RETRY = 3
const POLL_MS = 1000
/** complete（后端拼装转 SFTP）阶段最长等待时间，防止后端挂死时前端永久轮询。 */
const COMPLETE_TIMEOUT_MS = 10 * 60 * 1000

export type ConflictStrategy = 'error' | 'overwrite' | 'backup'

export interface InitResponse {
  receivedChunks: number[]
  missingChunks: number[]
  resumed: boolean
}

// ── REST 原语 ──

interface InitBody {
  uploadId: string
  sessionId: string
  remoteDir: string
  name: string
  size: number
  conflictStrategy: ConflictStrategy
}

async function initTransfer(body: InitBody): Promise<InitResponse> {
  const res = await axios.post(`${API_BASE}/sftp/transfer/init`, body)
  return res.data as InitResponse
}

async function postChunk(uploadId: string, index: number, data: Blob): Promise<void> {
  await axios.post(
    `${API_BASE}/sftp/transfer/chunk/${uploadId}?index=${index}`,
    data,
    {
      headers: { 'Content-Type': 'application/octet-stream' },
      timeout: 0,
    },
  )
}

async function completeTransfer(uploadId: string, signal?: AbortSignal): Promise<void> {
  await axios.post(`${API_BASE}/sftp/transfer/complete/${uploadId}`, null, {
    timeout: 0,
    signal,
  })
}

async function abortTransfer(uploadId: string): Promise<void> {
  await axios.post(`${API_BASE}/sftp/transfer/abort/${uploadId}`)
}

async function transferStatus(uploadId: string): Promise<{
  exists: boolean
  phase?: string
  transferred?: number
}> {
  const res = await axios.get(`${API_BASE}/sftp/transfer/status/${uploadId}`)
  return res.data
}

// ── uploadId：djb2 双段，确定性、长度 ≥16 ──

function djb2(s: string): string {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) >>> 0
  return h.toString(36).padStart(7, '0')
}

export function makeUploadId(seed: string): string {
  const rev = Array.from(seed).reverse().join('')
  return djb2(seed) + djb2(rev)
}

// ── 控制对象 ──

export interface UploadControl {
  paused: boolean
  aborted: boolean
  /**
   * 调度闸门：暂停任务恢复后若并行槽仍被占用，worker 继续等待而不是立刻发包。
   * 由上传 store 注入；直接调用 uploadFile 时可不提供。
   */
  canSend?: () => boolean
}

// ── 文件源：浏览器 File 或 Electron 本地路径 ──

export interface FileSource {
  name: string
  size: number
  file?: File
  localPath?: string
}

export interface UploadFileOptions {
  sessionId: string
  remoteDir: string
  source: FileSource
  conflictStrategy?: ConflictStrategy
  control: UploadControl
  /** 已确认上传到服务器的字节（含 complete 阶段 SFTP 转发）。 */
  onProgress?: (serverBytes: number) => void
  /**
   * 任务级随机分量：同文件上传到不同目录、或分属不同上传任务时不会复用同一
   * uploadId（同一任务重试应传入相同值以复用已落盘分片）。
   */
  uploadNonce?: string
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export class UploadCanceled extends Error {
  constructor() {
    super('已取消')
    this.name = 'UploadCanceled'
  }
}

/** 单文件上传主流程。 */
export async function uploadFile(opts: UploadFileOptions): Promise<void> {
  const { sessionId, remoteDir, source, control } = opts
  const conflict = opts.conflictStrategy ?? 'error'
  const mtime = source.file ? source.file.lastModified : 0
  // seed 纳入目标目录与任务级随机量：不同目录/不同任务不会复用同一 uploadId
  const seed = `${source.localPath ?? source.name}:${source.size}:${mtime}:${remoteDir}:${opts.uploadNonce ?? ''}`
  const uploadId = makeUploadId(seed)
  const totalChunks = Math.ceil(source.size / CHUNK_SIZE)

  const init = await initTransfer({
    uploadId, sessionId, remoteDir,
    name: source.name, size: source.size, conflictStrategy: conflict,
  })

  // 已落盘分片对应的字节数（用于进度基数）
  const bytesOfIndex = (i: number) =>
    i < totalChunks - 1 ? CHUNK_SIZE : source.size - CHUNK_SIZE * (totalChunks - 1)
  let confirmed = init.receivedChunks.reduce((s, i) => s + bytesOfIndex(i), 0)
  opts.onProgress?.(confirmed)

  // ── 分片 worker pool ──
  const missing = init.missingChunks
  let cursor = 0

  const readSlice = async (i: number): Promise<Blob> => {
    const start = i * CHUNK_SIZE
    const end = Math.min(source.size, start + CHUNK_SIZE)
    if (source.file) return source.file.slice(start, end)
    if (!source.localPath || !window.electronAPI) {
      throw new Error('缺少本地文件读取能力')
    }
    const buf = await window.electronAPI.readFileRange(source.localPath, start, end)
    return new Blob([buf])
  }

  const sendOne = async (i: number): Promise<void> => {
    let lastErr: unknown
    for (let attempt = 0; attempt < MAX_RETRY; attempt++) {
      if (control.aborted) throw new UploadCanceled()
      try {
        const blob = await readSlice(i)
        await postChunk(uploadId, i, blob)
        confirmed += bytesOfIndex(i)
        opts.onProgress?.(confirmed)
        return
      } catch (e) {
        lastErr = e
        await sleep(500 * (attempt + 1))
      }
    }
    throw lastErr instanceof Error ? lastErr : new Error('分片发送失败')
  }

  const worker = async (): Promise<void> => {
    for (;;) {
      if (control.aborted) throw new UploadCanceled()
      // 暂停中，或恢复后并行槽仍被其他任务占用时，让出等待（不占网络槽）
      if (control.paused || (control.canSend && !control.canSend())) {
        await sleep(300)
        continue
      }
      const i = missing[cursor++]
      if (i === undefined) return
      await sendOne(i)
    }
  }

  try {
    await Promise.all(Array.from({ length: MAX_WORKERS }, worker))

    // ── complete：后端拼装直转 SFTP；同时轮询转发进度 ──
    if (control.aborted) throw new UploadCanceled()
    const completeAbort = new AbortController()
    const timeoutId = window.setTimeout(() => completeAbort.abort(), COMPLETE_TIMEOUT_MS)
    const done = completeTransfer(uploadId, completeAbort.signal)
    let settled = false
    done.then(() => { settled = true }).catch(() => { settled = true })
    try {
      while (!settled) {
        if (control.aborted) {
          // 用户取消：停止等待转发结果，并通知后端清理暂存
          completeAbort.abort()
          await abortTransfer(uploadId).catch(() => undefined)
          throw new UploadCanceled()
        }
        await sleep(POLL_MS)
        if (settled) break // complete 已成功（其后任务目录会被删除，不能再查 status）
        const gone = new Error('上传任务已不存在（可能已被取消或服务重启）')
        try {
          const st = await transferStatus(uploadId)
          if (!st.exists) {
            // 任务已不存在（被 abort 清理或重启清扫）：转发不可能再成功
            completeAbort.abort()
            throw gone
          }
          if (typeof st.transferred === 'number' && st.transferred >= 0) {
            opts.onProgress?.(Math.max(confirmed, st.transferred))
          }
        } catch (inner) {
          if (inner === gone) throw inner
          /* 单次状态轮询网络失败不影响主流程 */
        }
      }
      await done
    } finally {
      window.clearTimeout(timeoutId)
    }
  } catch (e) {
    if (e instanceof UploadCanceled) {
      await abortTransfer(uploadId).catch(() => undefined)
      throw e
    }
    if (axios.isCancel(e)) {
      await abortTransfer(uploadId).catch(() => undefined)
      throw new Error('上传确认阶段超时（超过 10 分钟），已取消')
    }
    throw e
  }
}

/**
 * 整目录上传：
 * @param files 目录内全部文件（webkitRelativePath 保留相对结构）
 */
export async function uploadDirectory(
  sessionId: string,
  remoteBaseDir: string,
  files: File[],
  control: UploadControl,
  hooks: {
    onFileDone?: (done: number, total: number) => void
    /** 整目录累计已传输字节（已完成文件 + 当前文件进度） */
    onProgress?: (totalBytes: number) => void
  },
): Promise<string[]> {
  const failures: string[] = []
  /** 已完成文件的字节基数；onProgress 始终上报「整目录累计字节」，跨文件单调不减。 */
  let finishedBytes = 0
  hooks.onProgress?.(0)
  hooks.onFileDone?.(0, files.length)
  for (let fi = 0; fi < files.length; fi++) {
    if (control.aborted) throw new UploadCanceled()
    const f = files[fi]
    const rel = (f.webkitRelativePath || f.name).split('/')
    const name = rel.pop()!
    const subDir = rel.join('/')
    const dir = subDir ? `${remoteBaseDir}/${subDir}` : remoteBaseDir
    try {
      await uploadFile({
        sessionId,
        remoteDir: normalizeDir(dir),
        source: { name, size: f.size, file: f },
        control,
        onProgress: (currentFileBytes) => hooks.onProgress?.(finishedBytes + currentFileBytes),
      })
    } catch (e) {
      if (e instanceof UploadCanceled) throw e
      failures.push(`${f.webkitRelativePath || f.name}（${e instanceof Error ? e.message : '失败'}）`)
    }
    // 无论成功失败都推进基数，避免下一个文件进度回跳
    finishedBytes += f.size
    hooks.onProgress?.(finishedBytes)
    hooks.onFileDone?.(fi + 1, files.length)
  }
  return failures
}

function normalizeDir(d: string): string {
  const s = d.replace('\\', '/').replace(/\/+$/, '')
  return s === '' ? '/' : s
}
