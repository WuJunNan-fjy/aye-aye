import { useEffect, useState } from 'react'
import { message } from 'antd'
import { X, Save } from 'lucide-react'
import sftpApi from '@/api/sftpApi'

interface FileEditorModalProps {
  sessionId: string | null
  file: { path: string; name: string } | null
  onClose: () => void
  onSaved?: () => void
}

const EDITOR_MAX_BYTES = 65536

export default function FileEditorModal({ sessionId, file, onClose, onSaved }: FileEditorModalProps) {
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [truncated, setTruncated] = useState(false)

  useEffect(() => {
    if (!file || !sessionId) return
    let cancelled = false
    setLoading(true)
    setError(null)
    setTruncated(false)
    setContent('')
    sftpApi
      .readText(sessionId, file.path, EDITOR_MAX_BYTES)
      .then((res) => {
        if (cancelled) return
        setContent(res.content)
        setTruncated(res.content.length >= EDITOR_MAX_BYTES * 0.98)
      })
      .catch((e: any) => {
        if (!cancelled) setError(`读取失败：${e?.message || e}`)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [file, sessionId])

  useEffect(() => {
    if (!file) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [file, onClose])

  if (!file) return null

  const handleSave = async () => {
    if (!sessionId) return
    setSaving(true)
    setError(null)
    try {
      await sftpApi.writeText(sessionId, file.path, content)
      message.success(`已保存 ${file.name}`)
      onSaved?.()
    } catch (e: any) {
      setError(`保存失败：${e?.message || e}`)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="file-editor-overlay" onClick={onClose}>
      <div className="file-editor-modal" onClick={(e) => e.stopPropagation()}>
        <div className="file-editor-head">
          <div className="file-editor-title">
            <span className="file-editor-badge">文件</span>
            <span className="file-editor-path" title={file.path}>{file.path}</span>
          </div>
          <div className="file-editor-actions">
            <button className="dsh-btn dsh-btn-primary dsh-btn-sm" onClick={handleSave} disabled={saving || loading}>
              <Save className={saving ? 'dsh-spin' : undefined} /> {saving ? '保存中…' : '保存'}
            </button>
            <button className="dsh-icon-btn" title="关闭 (Esc)" onClick={onClose}>
              <X />
            </button>
          </div>
        </div>

        {truncated && (
          <div className="file-editor-truncated">
            文件较大，已按前 {EDITOR_MAX_BYTES.toLocaleString()} 字节截断显示，保存会覆盖为截断内容。
          </div>
        )}
        {error && <div className="file-editor-error">{error}</div>}

        <div className="file-editor-body">
          {loading ? (
            <div className="file-editor-loading">读取中…</div>
          ) : (
            <textarea
              className="file-editor-textarea"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              spellCheck={false}
              autoFocus
            />
          )}
        </div>
      </div>
    </div>
  )
}
