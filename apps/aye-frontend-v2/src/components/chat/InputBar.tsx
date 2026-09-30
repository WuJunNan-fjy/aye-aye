import {
  forwardRef,
  memo,
  useImperativeHandle,
  useRef,
  useState,
  type DragEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { message } from 'antd'
import { Paperclip, ArrowUp, CircleStop } from 'lucide-react'
import { uploadAttachment, type ChatAttachment } from '@/api/agentApi'
import { getFileIcon } from '@/utils/fileIcons'
import FolderDropChoice, { getFolderChoice, resetFolderChoice, type FolderChoice } from './FolderDropChoice'
import { useHostStore, selectSessionId } from '@/store/useHostStore'
import { useUploadStore } from '@/store/useUploadStore'
import {
  readDroppedEntries,
  collectDirectoryFiles,
  type DropEntry,
} from '@/lib/dropEntries'

/**
 * Composer 输入条 —— 视觉 1:1 复刻 dsh InputBar（inputbar.css）。
 * 交互在 dsh 键盘/提交路径之上，重新设计了 v1 的「拖文件进对话」精髓：
 *   - 拖拽本地文件到输入卡：桌面端仅保存本机绝对路径（零上传），浏览器端走 /agent/upload 降级
 *   - 从文件树拖入服务器文件 → @path 标签 chip（AI 经 SFTP 工具读写）
 *   - 提交载荷含 text + attachments + fileTags，供后端注入上下文
 * Translate：dsh machine/keyboard/inputActions 三端 → 受控 props + 内部 chips 状态。
 */

export interface FileTag {
  path: string
  name: string
  isDir: boolean
}

export interface ComposerSubmitPayload {
  text: string
  /** 已上传成功的本地附件（{name,path} 提交后端注入上下文） */
  attachments: ChatAttachment[]
  /** 服务器文件 @path 标签 */
  fileTags: FileTag[]
}

export interface InputBarHandle {
  /** 命令面板选中/Escape 回焦路径：把焦点还给 composer。 */
  focusComposer(): void
  /** 发送后清空草稿与 chips（由父组件在 submit 成功后调用）。 */
  reset(): void
}

/** 拖拽数据源标识：文件树行 onDragStart 写入，InputBar 读取。 */
export const DRAG_FILE_MIME = 'application/aye-file'

interface LocalAttachment {
  id: string
  name: string
  size: number
  mimetype?: string
  status: 'uploading' | 'done' | 'error'
  progress: number
  path?: string
  /** 桌面端目录引用标记 */
  isDir?: boolean
  /** 桌面端主进程颁发的路径来源凭证 */
  pathToken?: string
}

interface InputBarProps {
  value: string
  onChange: (next: string) => void
  onSubmit: (payload: ComposerSubmitPayload) => void
  onStop?: () => void
  /** 会话运行中：主按钮变停止 */
  running?: boolean
  placeholder?: string
  disabled?: boolean
  /**
   * hero：空会话居中的大卡片（textarea 3 行高，无底部外距）；
   * composer：开始对话后落底的常规输入条（默认）。同一实例切 variant，draft/焦点不断。
   */
  variant?: 'hero' | 'composer'
  /** 右下角工具槽：模型座等 */
  trailing?: ReactNode
  /** 左下角工具槽：命令菜单等 */
  tools?: ReactNode
  notice?: { text: string; error?: boolean } | null
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const MAX_UPLOAD = 200 * 1024 * 1024

/** 桌面端最小能力面：只需要路径转换；浏览器模式为 undefined。 */
interface DesktopPathApi {
  getPathForFile: (file: File) => string
  grantLocalPath?: (path: string) => Promise<string>
}

function getDesktopPathApi(): DesktopPathApi | null {
  const api = (window as unknown as { electronAPI?: DesktopPathApi }).electronAPI
  return api && typeof api.getPathForFile === 'function' ? api : null
}

const desktopApi = getDesktopPathApi()

// memo：流式期间父页面每帧重渲染，输入条 props（value/回调/插槽）稳定时无需跟随。
const InputBar = memo(forwardRef<InputBarHandle, InputBarProps>(function InputBar({
  value, onChange, onSubmit, onStop, running = false,
  placeholder = '输入消息，Enter 发送，Shift+Enter 换行…（可直接拖入本地文件或服务器文件）',
  disabled = false, variant = 'composer', trailing, tools, notice,
}, ref) {
  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [atts, setAtts] = useState<LocalAttachment[]>([])
  const [tags, setTags] = useState<FileTag[]>([])
  const [dragging, setDragging] = useState(false)
  /**
   * 等待用户二选一的文件夹队列（同时保留原始 entry，File 无法反查 entry）。
   * 一次拖入多个文件夹时逐个弹卡，不能只留最后一个。
   */
  type FolderTarget = { folder: File; entry: FileSystemDirectoryEntry }
  const [pendingFolders, setPendingFolders] = useState<FolderTarget[]>([])
  const currentFolder = pendingFolders[0] ?? null
  /** 仅用于触发重渲染：模块级 remembered 变更不在 React 状态内。 */
  const [choiceVer, setChoiceVer] = useState(0)

  useImperativeHandle(ref, () => ({
    focusComposer: () => { inputRef.current?.focus({ preventScroll: true }) },
    reset: () => { setAtts([]); setTags([]) },
  }))

  const empty = value.trim() === ''
  const uploading = atts.some((a) => a.status === 'uploading')
  const hasPayload = !empty || atts.some((a) => a.status === 'done') || tags.length > 0
  const primaryStops = running

  /**
   * 拖入/选择的本地文件：
   *  - 桌面端：File → 本机绝对路径，直接 done，不调用任何上传接口（零上传）；
   *  - 浏览器：保留 /agent/upload 上传降级（后端工具复用同一份白名单）。
   */
  const handleFiles = async (files: FileList | File[]): Promise<void> => {
    for (const file of Array.from(files)) {
      if (file.size > MAX_UPLOAD) {
        message.warning(`${file.name}: 文件超过 200MB 限制`)
        continue
      }
      const id = `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

      if (desktopApi) {
        const localPath = desktopApi.getPathForFile(file)
        const pathToken = desktopApi.grantLocalPath
          ? await desktopApi.grantLocalPath(localPath) : ''
        setAtts((prev) => [...prev, {
          id, name: file.name, size: file.size,
          mimetype: file.type || 'application/octet-stream',
          status: 'done', progress: 100, path: localPath, pathToken,
        }])
        continue
      }

      setAtts((prev) => [...prev, {
        id, name: file.name, size: file.size,
        mimetype: file.type || 'application/octet-stream',
        status: 'uploading', progress: 0,
      }])
      try {
        const up = await uploadAttachment(file, (p) => {
          setAtts((prev) => prev.map((a) => (a.id === id ? { ...a, progress: p } : a)))
        })
        setAtts((prev) => prev.map((a) =>
          (a.id === id ? { ...a, path: up.path, progress: 100, status: 'done' } : a)))
      } catch (e: any) {
        setAtts((prev) => prev.map((a) => (a.id === id ? { ...a, status: 'error' } : a)))
        message.error(`${file.name}: ${e?.message || '上传失败'}`)
      }
    }
  }

  /** 目录作为上下文引用：chip 保存目录本机路径，isDir=true。 */
  const attachLocalDir = async (folder: File): Promise<void> => {
    if (!desktopApi) return
    const localPath = desktopApi.getPathForFile(folder)
    const pathToken = desktopApi.grantLocalPath
      ? await desktopApi.grantLocalPath(localPath) : ''
    const id = `att-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    setAtts((prev) => [...prev, {
      id, name: folder.name, size: folder.size,
      status: 'done', progress: 100, path: localPath, isDir: true, pathToken,
    }])
  }

  /** 目录转部署轨：递归收集内部文件（保留相对路径）→ 入上传队列。 */
  const uploadFolderToServer = async (
    target: { folder: File; entry: FileSystemDirectoryEntry },
  ): Promise<void> => {
    const sid = selectSessionId(useHostStore.getState())
    if (!sid) {
      message.error('请先在右侧「服务器」面板连接主机')
      return
    }
    let files: File[]
    try {
      files = await collectDirectoryFiles(target.entry, target.folder.name)
    } catch (err) {
      message.error(`读取文件夹失败：${target.folder.name}（${err instanceof Error ? err.message : err}）`)
      return
    }
    if (files.length === 0) {
      message.warning('该文件夹为空')
      return
    }
    const remoteDir = useUploadStore.getState().treeCurrentPath || '/'
    useUploadStore.getState().enqueue({
      kind: 'directory', sessionId: sid, remoteDir, files,
    })
    message.success(`已加入上传队列：${target.folder.name} → ${remoteDir}`)
  }

  /** FolderDropChoice 回调：消费队首并按选择执行，队列中还有则继续弹下一卡。 */
  const onFolderChoose = (choice: FolderChoice): void => {
    const target = pendingFolders[0]
    setPendingFolders((q) => q.slice(1))
    // pick() 已在弹卡内部写入模块级 remembered，+1 触发重渲染以显示「改回默认」按钮
    setChoiceVer((v) => v + 1)
    if (!target) return
    if (choice === 'reference') void attachLocalDir(target.folder)
    else void uploadFolderToServer(target)
  }

  /** 服务器文件 → @path 标签 chip。 */
  const addFileTag = (tag: FileTag): void => {
    setTags((prev) => (prev.some((t) => t.path === tag.path) ? prev : [...prev, tag]))
  }

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (e.key === 'Enter' && e.shiftKey) return // Shift+Enter 无条件换行
    if (e.key === 'Enter') {
      // IME 组合 Enter 是选词，不发送。
      if (e.nativeEvent.isComposing || (e.nativeEvent as { keyCode?: number }).keyCode === 229) return
      e.preventDefault()
      if (e.repeat) return
      if (disabled || uploading || running) return
      if (empty && !hasPayload) return
      handleSend()
    }
  }

  const handleSend = (): void => {
    if (uploading || running) return
    const text = value.trim()
    const readyAtts = atts
      .filter((a) => a.status === 'done' && a.path)
      .map((a) => ({
        name: a.name,
        path: a.path!,
        isDirectory: a.isDir ?? false,
        ...(a.pathToken ? { pathToken: a.pathToken } : {}),
      }))
    if (!text && readyAtts.length === 0 && tags.length === 0) return
    onSubmit({ text, attachments: readyAtts, fileTags: tags })
    onChange('')
    setAtts([])
    setTags([])
  }

  const onPrimary = (): void => {
    if (primaryStops) {
      onStop?.()
      return
    }
    if (!disabled && !uploading && !running) handleSend()
  }

  // ── 拖拽（本地文件 / 服务器 @path）──
  const canAcceptDrop = (e: DragEvent<HTMLDivElement>): boolean => {
    const types = e.dataTransfer.types
    if (!types) return false
    return Array.from(types).includes('Files') || Array.from(types).includes(DRAG_FILE_MIME)
  }

  const onDragOver = (e: DragEvent<HTMLDivElement>): void => {
    if (!canAcceptDrop(e)) return
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
    if (!dragging) setDragging(true)
  }

  const onDragLeave = (e: DragEvent<HTMLDivElement>): void => {
    if (dragging && !e.currentTarget.contains(e.relatedTarget as Node | null)) {
      setDragging(false)
    }
  }

  const onDrop = async (e: DragEvent<HTMLDivElement>): Promise<void> => {
    setDragging(false)
    if (disabled) return
    if (e.dataTransfer.types?.includes('Files')) {
      e.preventDefault()
      let entries: DropEntry[]
      try {
        entries = await readDroppedEntries(e)
      } catch (err) {
        message.error(`读取拖入内容失败：${err instanceof Error ? err.message : err}`)
        return
      }
      const dirs = entries.filter((x) => x.kind === 'dir')
      const looseFiles = entries.filter((x) => x.kind === 'file').map((x) => x.file)

      if (desktopApi && dirs.length > 0) {
        // 散文件照常作为本地附件
        if (looseFiles.length > 0) void handleFiles(looseFiles)
        for (const d of dirs) {
          if (!d.entry?.isDirectory) continue
          const target = {
            folder: d.file,
            entry: d.entry as FileSystemDirectoryEntry,
          }
          const rememberedChoice = getFolderChoice()
          if (rememberedChoice === 'reference') {
            void attachLocalDir(d.file)
          } else if (rememberedChoice === 'upload') {
            void uploadFolderToServer(target)
          } else {
            // 多文件夹排队，逐个弹卡确认
            setPendingFolders((q) => [...q, target])
          }
        }
        return
      }

      // 浏览器模式：散文件走 /agent/upload；文件夹必须递归收集后入上传队列——
      // 目录对应的 File 对象 size 恒为 0，直接上传会变成一个 0 字节假文件。
      if (looseFiles.length > 0) void handleFiles(looseFiles)
      for (const d of dirs) {
        if (!d.entry?.isDirectory) continue
        void uploadFolderToServer({
          folder: d.file,
          entry: d.entry as FileSystemDirectoryEntry,
        })
      }
      return
    }
    const payload = e.dataTransfer.getData(DRAG_FILE_MIME)
    if (payload) {
      e.preventDefault()
      try {
        const info = JSON.parse(payload) as { path?: string; name?: string; type?: string }
        if (info.path) {
          addFileTag({
            path: info.path,
            name: info.name || info.path.split('/').pop() || info.path,
            isDir: info.type === 'directory',
          })
          message.success(`已添加文件：${info.name || info.path}`)
        }
      } catch { /* 忽略无效载荷 */ }
    }
  }

  return (
    <div
      className={`inp-root inp-root-${variant} ${dragging ? 'inp-dragover' : ''}`}
      data-drag-over={dragging || undefined}
    >
      {notice !== null && notice !== undefined && (
        <div className={`inp-notice ${notice.error ? 'inp-noticeError' : ''}`} role="status">
          {notice.text}
        </div>
      )}
      <div
        className="inp-card"
        data-composer-card
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          style={{ display: 'none' }}
          onChange={(e) => {
            if (e.target.files?.length) void handleFiles(e.target.files)
            e.target.value = ''
          }}
        />

        {(atts.length > 0 || tags.length > 0) && (
          <div className="inp-chips">
            {tags.map((t) => {
              const fi = getFileIcon(t.name, t.isDir)
              return (
                <span key={t.path} className="inp-chip" data-file-tag title={t.path}>
                  <span className="inp-chipIcon" style={{ color: fi.color }}>{fi.icon}</span>
                  <span className="inp-chipName">{t.name}</span>
                  <button
                    type="button"
                    className="inp-chipRemove"
                    aria-label="移除"
                    title="移除"
                    disabled={disabled || running}
                    onClick={() => setTags((prev) => prev.filter((x) => x.path !== t.path))}
                  >
                    ✕
                  </button>
                </span>
              )
            })}
            {atts.map((a) => {
              const fi = getFileIcon(a.name)
              return (
                <span key={a.id} className="inp-chip" data-attachment title={a.name}>
                  <span className="inp-chipIcon" style={{ color: fi.color }}>{fi.icon}</span>
                  <span className="inp-chipName">{a.name}</span>
                  <span className="inp-chipSize">{formatSize(a.size)}</span>
                  {a.status === 'uploading' && <span className="inp-chipProgress">{a.progress}%</span>}
                  {a.status === 'error' && <span className="inp-chipError">上传失败</span>}
                  {a.status === 'done' && a.isDir && <span className="inp-chipTag">目录</span>}
                  {a.status === 'done' && desktopApi && !a.isDir && (
                    <span className="inp-chipTag">本地</span>
                  )}
                  <button
                    type="button"
                    className="inp-chipRemove"
                    aria-label="移除"
                    title="移除"
                    disabled={disabled || running}
                    onClick={() => setAtts((prev) => prev.filter((x) => x.id !== a.id))}
                  >
                    ✕
                  </button>
                </span>
              )
            })}
          </div>
        )}

        <textarea
          ref={inputRef}
          className="inp-textarea"
          value={value}
          placeholder={placeholder}
          rows={2}
          disabled={disabled}
          onChange={(e) => { onChange(e.target.value) }}
          onKeyDown={onKeyDown}
        />
        <div className="inp-row">
          <div className="inp-tools">
            <button
              type="button"
              className="inp-add"
              aria-label="附件"
              title="添加本地附件"
              disabled={disabled}
              onMouseDown={(e) => { e.preventDefault() }}
              onClick={() => fileInputRef.current?.click()}
            >
              <Paperclip style={{ fontSize: 14 }} />
            </button>
            {tools}
            {getFolderChoice() !== null && (
              <button
                type="button"
                className="inp-choiceReset"
                title="恢复每次询问"
                onClick={() => { resetFolderChoice(); setChoiceVer((v) => v + 1) }}
              >
                文件夹默认：{getFolderChoice() === 'reference' ? '引用' : '上传'} ✎
              </button>
            )}
          </div>
          <div className="inp-trailing">
            {trailing}
            <button
              type="button"
              className={`inp-primary ${primaryStops ? 'inp-primary-stop' : ''}`}
              aria-label={primaryStops ? '停止' : '发送'}
              title={primaryStops ? '停止生成' : '发送 (Enter)'}
              disabled={primaryStops ? onStop === undefined : (!hasPayload || disabled || uploading || running)}
              onMouseDown={(e) => { e.preventDefault() }}
              onClick={onPrimary}
            >
              {primaryStops
                ? (
                  /* 停止：圆内方块（当代聊天应用标准 stop 语义） */
                  <CircleStop style={{ fontSize: 15 }} aria-hidden />
                )
                : (
                  /* 发送：向上箭头 */
                  <ArrowUp style={{ fontSize: 17 }} strokeWidth={2.25} aria-hidden />
                )}
            </button>
          </div>
        </div>
      </div>
      {currentFolder && (
        <FolderDropChoice
          folderName={pendingFolders.length > 1
            ? `${currentFolder.folder.name}（另有 ${pendingFolders.length - 1} 个待处理）`
            : currentFolder.folder.name}
          onChoose={onFolderChoose}
          onCancel={() => setPendingFolders((q) => q.slice(1))}
        />
      )}
    </div>
  )
}))

InputBar.displayName = 'InputBar'

export default InputBar
