import { useMemo, useState } from 'react'
import {
  Upload, Pause, Play, X, RotateCw, ChevronUp, FileArchive, Folder as FolderIcon, File as FileIcon,
} from 'lucide-react'
import { useUploadStore, type UploadTask } from '@/store/useUploadStore'
import { useHostStore, selectSessionId } from '@/store/useHostStore'
import { getFileIcon } from '@/utils/fileIcons'

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function KindIcon({ task }: { task: UploadTask }) {
  if (task.kind === 'archive') return <FileArchive size={14} />
  if (task.kind === 'directory') return <FolderIcon size={14} />
  const fi = getFileIcon(task.name, false)
  return <span style={{ color: fi.color }}>{fi.icon}</span>
}

function TaskRow({ task }: { task: UploadTask }) {
  const { pause, resume, cancel, retry } = useUploadStore()
  const pct = task.size > 0 ? Math.min(100, Math.round((task.sentBytes / task.size) * 100)) : 0
  const active = task.status === 'uploading'

  return (
    <div className={`up-row up-${task.status}`}>
      <span className="up-row-icon"><KindIcon task={task} /></span>
      <div className="up-main">
        <div className="up-line">
          <span className="up-name" title={task.name}>{task.name}</span>
          <span className="up-meta">
            {task.kind === 'directory' && task.filesTotal != null
              ? `${task.filesDone ?? 0}/${task.filesTotal} 文件 · `
              : ''}
            {fmtSize(task.sentBytes)} / {fmtSize(task.size)}
            {task.status === 'error' && task.error ? ` · ${task.error}` : ''}
            {task.status === 'canceled' ? ' · 已取消' : ''}
            {task.status === 'done' ? ' · 完成' : ''}
          </span>
        </div>
        <div className="up-bar">
          <div className="up-bar-fill" style={{ width: `${active || task.status === 'paused' ? pct : 100}%` }} />
        </div>
      </div>
      <span className="up-actions">
        {task.status === 'uploading' && (
          <button className="up-act" title="暂停" onClick={() => pause(task.id)}><Pause size={13} /></button>
        )}
        {task.status === 'paused' && (
          <button className="up-act" title="继续" onClick={() => resume(task.id)}><Play size={13} /></button>
        )}
        {task.status === 'error' && (
          <button className="up-act" title="重试" onClick={() => retry(task.id)}><RotateCw size={13} /></button>
        )}
        {(task.status === 'uploading' || task.status === 'paused' || task.status === 'queued') && (
          <button className="up-act danger" title="取消" onClick={() => cancel(task.id)}><X size={13} /></button>
        )}
        {(task.status === 'done' || task.status === 'error' || task.status === 'canceled') && (
          <button className="up-act" title="移除记录" onClick={() => useUploadStore.getState().remove(task.id)}><X size={13} /></button>
        )}
      </span>
    </div>
  )
}

export default function UploadManager() {
  const tasks = useUploadStore((s) => s.tasks)
  // 只展示当前 SSH 会话（主机）的上传任务
  const sid = useHostStore(selectSessionId)
  const [expanded, setExpanded] = useState(false)

  const visible = useMemo(
    () => tasks.filter((t) => t.sessionId === sid),
    [tasks, sid],
  )
  const activeCount = visible.filter(
    (t) => t.status === 'uploading' || t.status === 'queued' || t.status === 'paused',
  ).length
  const totalSent = visible.reduce((s, t) => s + t.sentBytes, 0)
  const totalSize = visible.reduce((s, t) => s + t.size, 0)
  const totalPct = totalSize > 0 ? Math.round((totalSent / totalSize) * 100) : 0

  if (visible.length === 0) return null

  return (
    <div className={`up-manager ${expanded ? 'up-expanded' : ''}`}>
      <button className="up-head" onClick={() => setExpanded((e) => !e)}>
        <Upload size={13} />
        <span className="up-head-text">
          {activeCount > 0 ? `上传中 ${activeCount} 项 · ${totalPct}%` : `上传任务 ${visible.length} 项`}
        </span>
        <ChevronUp size={13} className={`up-chevron ${expanded ? 'up-chevron-down' : ''}`} />
      </button>
      {expanded && (
        <div className="up-list">
          {visible.map((t) => <TaskRow key={t.id} task={t} />)}
        </div>
      )}
    </div>
  )
}
