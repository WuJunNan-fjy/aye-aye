import { create } from 'zustand'
import sftpApi from '@/api/sftpApi'
import {
  uploadFile,
  uploadDirectory,
  type ConflictStrategy,
  type UploadControl,
} from '@/api/transferApi'

export type TaskStatus =
  | 'queued'
  | 'uploading'
  | 'paused'
  | 'done'
  | 'error'
  | 'canceled'

export type UploadKind = 'file' | 'archive' | 'directory'

export interface UploadSpec {
  kind: UploadKind
  sessionId: string
  remoteDir: string
  conflictStrategy?: ConflictStrategy
  // file
  file?: File
  // archive（Electron 归档产物：只有本地路径）
  localPath?: string
  name?: string
  size?: number
  // directory
  files?: File[]
}

export interface UploadTask {
  id: string
  name: string
  size: number
  sentBytes: number
  status: TaskStatus
  kind: UploadKind
  error?: string
  filesTotal?: number
  filesDone?: number
  remoteDir: string
  /** 所属 SSH 会话：上传列表按当前会话过滤，避免多主机任务串显。 */
  sessionId: string
}

interface UploadStore {
  tasks: UploadTask[]
  /** 文件树当前远程目录：聊天拖文件夹选「上传到服务器」时的目标 */
  treeCurrentPath: string
  setTreeCurrentPath: (path: string) => void
  enqueue: (spec: UploadSpec) => string
  pause: (id: string) => void
  resume: (id: string) => void
  cancel: (id: string) => void
  retry: (id: string) => void
  remove: (id: string) => void
}

const MAX_PARALLEL = 2

// 模块级运行时表（不入 React 状态）
const controls = new Map<string, UploadControl>()
const specs = new Map<string, UploadSpec>()
/** 任务级随机量：同一任务重试复用（续传分片），不同任务不复用 uploadId。 */
const nonces = new Map<string, string>()
let seq = 0

function newNonce(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36)
}

/** 归档任务上传终态后清理主进程生成的临时归档文件（失败保留以便重试）。 */
function cleanupArchiveIfAny(id: string): void {
  const sp = specs.get(id)
  if (sp?.kind === 'archive' && sp.localPath) {
    void window.electronAPI?.cleanupArchive?.(sp.localPath)
  }
}

function nextId(): string {
  seq += 1
  return `up-${Date.now()}-${seq}`
}

function taskBaseName(spec: UploadSpec): string {
  if (spec.kind === 'file') return spec.file!.name
  if (spec.kind === 'archive') return spec.name ?? 'archive'
  const top = new Set(
    (spec.files ?? []).map((f) => f.webkitRelativePath.split('/')[0]),
  )
  return top.size === 1 ? [...top][0] : `${spec.files?.length ?? 0} 个文件`
}

export const useUploadStore = create<UploadStore>((set, get) => {
  const patch = (id: string, p: Partial<UploadTask>) =>
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, ...p } : t)),
    }))

  /** 调度：并行槽有空就启动下一个 queued（暂停任务不占槽位）。 */
  const tick = () => {
    const running = get().tasks.filter((t) => t.status === 'uploading').length
    if (running >= MAX_PARALLEL) return
    const next = get().tasks.find((t) => t.status === 'queued')
    if (next) void runTask(next.id)
  }

  const runTask = async (id: string) => {
    const spec = specs.get(id)
    const control = controls.get(id)
    if (!spec || !control) return
    const uploadNonce = nonces.get(id)
    patch(id, { status: 'uploading', error: undefined })
    try {
      if (spec.kind === 'directory') {
        const files = spec.files ?? []
        // 先创建所有子目录（去重、排序保证父目录在前）
        const dirs = [...new Set(
          files.map((f) => {
            const parts = f.webkitRelativePath.split('/')
            parts.pop()
            return parts.join('/')
          }).filter(Boolean),
        )].sort((a, b) => a.length - b.length)
        for (const d of dirs) {
          await sftpApi.mkdir(spec.sessionId, `${spec.remoteDir}/${d}`)
        }
        const failures = await uploadDirectory(
          spec.sessionId, spec.remoteDir, files, control, {
            onFileDone: (done, total) =>
              patch(id, { filesDone: done, filesTotal: total }),
            onProgress: (b) => patch(id, { sentBytes: b }),
          },
        )
        if (failures.length > 0) {
          throw new Error(`${failures.length} 个文件失败：${failures.slice(0, 3).join('；')}`)
        }
      } else if (spec.kind === 'archive') {
        await uploadFile({
          sessionId: spec.sessionId,
          remoteDir: spec.remoteDir,
          source: {
            name: spec.name!,
            size: spec.size!,
            localPath: spec.localPath,
          },
          conflictStrategy: spec.conflictStrategy,
          control,
          uploadNonce,
          onProgress: (b) => patch(id, { sentBytes: b }),
        })
      } else {
        await uploadFile({
          sessionId: spec.sessionId,
          remoteDir: spec.remoteDir,
          source: {
            name: spec.file!.name,
            size: spec.file!.size,
            file: spec.file,
          },
          conflictStrategy: spec.conflictStrategy,
          control,
          uploadNonce,
          onProgress: (b) => patch(id, { sentBytes: b }),
        })
      }
      patch(id, { status: 'done', sentBytes: get().tasks.find((t) => t.id === id)?.size ?? 0 })
      cleanupArchiveIfAny(id)
    } catch (e) {
      if (e instanceof Error && e.name === 'UploadCanceled') {
        patch(id, { status: 'canceled' })
        cleanupArchiveIfAny(id)
      } else {
        patch(id, {
          status: 'error',
          error: e instanceof Error ? e.message : '上传失败',
        })
      }
    } finally {
      window.setTimeout(tick, 0)
    }
  }

  return {
    tasks: [],
    treeCurrentPath: '/',
    setTreeCurrentPath: (path) => set({ treeCurrentPath: path }),

    enqueue: (spec: UploadSpec) => {
      const id = nextId()
      const name = taskBaseName(spec)
      const size =
        spec.kind === 'file'
          ? spec.file!.size
          : spec.kind === 'archive'
            ? spec.size ?? 0
            : (spec.files ?? []).reduce((s, f) => s + f.size, 0)
      controls.set(id, {
        paused: false,
        aborted: false,
        // 暂停恢复后若并行槽仍满（被其他 uploading 任务占用），继续等待
        canSend: () =>
          get().tasks.filter((t) => t.status === 'uploading').length <= MAX_PARALLEL,
      })
      specs.set(id, spec)
      nonces.set(id, newNonce())
      set((s) => ({
        tasks: [...s.tasks, {
          id, name, size, sentBytes: 0,
          status: 'queued', kind: spec.kind,
          filesTotal: spec.kind === 'directory' ? spec.files?.length : undefined,
          filesDone: 0,
          remoteDir: spec.remoteDir,
          sessionId: spec.sessionId,
        }],
      }))
      window.setTimeout(tick, 0)
      return id
    },

    pause: (id) => {
      const c = controls.get(id)
      if (c) c.paused = true
      patch(id, { status: 'paused' })
    },

    resume: (id) => {
      const c = controls.get(id)
      if (c) c.paused = false
      patch(id, { status: 'uploading' })
      window.setTimeout(tick, 0)
    },

    cancel: (id) => {
      const c = controls.get(id)
      if (c) c.aborted = true
      // queued 任务直接标记（worker 从未启动）
      const t = get().tasks.find((x) => x.id === id)
      if (t?.status === 'queued') {
        patch(id, { status: 'canceled' })
        cleanupArchiveIfAny(id)
      }
    },

    retry: (id) => {
      const spec = specs.get(id)
      if (!spec) return
      controls.set(id, { paused: false, aborted: false })
      patch(id, { status: 'queued', sentBytes: 0, error: undefined })
      window.setTimeout(tick, 0)
    },

    remove: (id) => {
      controls.delete(id)
      specs.delete(id)
      nonces.delete(id)
      // 错误任务被移除意味着放弃重试，临时归档一并清理（终态已清过则为幂等 no-op）
      cleanupArchiveIfAny(id)
      set((s) => ({ tasks: s.tasks.filter((t) => t.id !== id) }))
    },
  }
})
