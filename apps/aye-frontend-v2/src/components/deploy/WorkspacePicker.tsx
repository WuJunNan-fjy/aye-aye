/**
 * 工作区选择器 —— 1:1 复刻 dsh ui-workspace/WorkspacePickFlow。
 *
 * 形态：锚定入口 chip、向上弹出的菜单卡（portal 定位），不是居中模态框。
 * - 项目列表行：40px 高、hover 填充、选中仅尾部 check（无填充）；
 * - 底部固定动作区：选择文件夹 / 从 Git 拉取（内联展开）/ 解除绑定（danger）；
 * - 外部点击、Esc、滚动/缩放跟随锚点；列表区内部滚动。
 * 视觉数值见 WorkspacePicker.css（wp- 前缀，全部走 --dsw-* token）。
 */

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown, Folder, FolderPlus, GitFork, Loader2, Unlink } from 'lucide-react'
import {
  bindWorkspace,
  cloneGitWorkspace,
  createWorkspace,
  listWorkspaces,
  unbindWorkspace,
  type Workspace,
} from '@/api/deployApi'
import './WorkspacePicker.css'

interface Props {
  open: boolean
  /** 弹出菜单锚定的入口元素 */
  anchorRef: RefObject<HTMLElement | null>
  /** 已有会话时传入，选中即绑定；新会话（undefined）由后端启动 turn 时绑定 */
  sessionId?: string
  selectedId?: number
  onClose: () => void
  /** 选中传工作区；解除绑定时传 null */
  onSelect: (ws: Workspace | null) => void
}

const isDesktop = (): boolean =>
  typeof window !== 'undefined' && !!window.electronAPI?.selectWorkspace

export default function WorkspacePicker({ open, anchorRef, sessionId, selectedId, onClose, onSelect }: Props) {
  const [items, setItems] = useState<Workspace[]>([])
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [gitUrl, setGitUrl] = useState('')
  const [branch, setBranch] = useState('')
  const [showGit, setShowGit] = useState(false)

  const cardRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)

  const refresh = useCallback(async () => {
    try {
      setItems(await listWorkspaces())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoaded(true)
    }
  }, [])

  useEffect(() => {
    if (open) {
      setError('')
      setLoaded(false)
      void refresh()
    } else {
      setShowGit(false)
    }
  }, [open, refresh])

  /* portal 定位：向上展开（bottom = 锚点顶 - 4px 间隙），视口边缘留 12px。 */
  useLayoutEffect(() => {
    if (!open) {
      setPos(null)
      return
    }
    const place = () => {
      const r = anchorRef.current?.getBoundingClientRect()
      const el = cardRef.current
      if (!r || !el) return
      const MARGIN = 12
      const lw = el.offsetWidth
      const lh = el.offsetHeight
      const left = Math.min(Math.max(r.left, MARGIN), window.innerWidth - lw - MARGIN)
      const top = Math.min(Math.max(r.top - lh - 4, MARGIN), window.innerHeight - lh - MARGIN)
      setPos({ left, top })
    }
    place()
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => {
      window.removeEventListener('scroll', place, true)
      window.removeEventListener('resize', place)
    }
  }, [open, anchorRef, items.length, showGit, error])

  /* 外部点击 / Esc 关闭（portal 节点在锚点子树之外，两处都要判）。 */
  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (!(e.target instanceof Node)) return
      if (anchorRef.current?.contains(e.target)) return
      if (cardRef.current?.contains(e.target)) return
      onClose()
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open, anchorRef, onClose])

  const choose = useCallback(
    async (ws: Workspace) => {
      try {
        setBusy(true)
        if (sessionId) await bindWorkspace(sessionId, ws.id)
        onSelect(ws)
        onClose()
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e))
      } finally {
        setBusy(false)
      }
    },
    [sessionId, onSelect, onClose],
  )

  const pickLocal = useCallback(async () => {
    const api = window.electronAPI
    if (!api) return
    try {
      setBusy(true)
      setError('')
      const path = await api.selectWorkspace()
      if (!path) return
      const pathToken = await api.grantLocalPath?.(path) ?? ''
      const ws = await createWorkspace({ path, pathToken })
      if (sessionId) await bindWorkspace(sessionId, ws.id)
      onSelect(ws)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }, [sessionId, onSelect, onClose])

  const cloneGit = useCallback(async () => {
    try {
      setBusy(true)
      setError('')
      const ws = await cloneGitWorkspace(gitUrl.trim(), branch.trim() || undefined)
      if (sessionId) await bindWorkspace(sessionId, ws.id)
      onSelect(ws)
      onClose()
      setGitUrl('')
      setBranch('')
      setShowGit(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }, [gitUrl, branch, sessionId, onSelect, onClose])

  const unbind = useCallback(async () => {
    try {
      setBusy(true)
      if (sessionId) await unbindWorkspace(sessionId)
      onSelect(null)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }, [sessionId, onSelect, onClose])

  if (!open) return null

  const desktop = isDesktop()

  return createPortal(
    <div
      ref={cardRef}
      className="wp-card"
      role="menu"
      style={pos ? { left: pos.left, top: pos.top } : { visibility: 'hidden' }}
    >
      <div className="wp-view">
        {!loaded
          ? <div className="wp-status" role="status">加载中…</div>
          : items.length === 0
            ? <div className="wp-status">暂无项目，请在下方添加</div>
            : items.map((ws) => {
              const active = ws.id === selectedId
              return (
                <button
                  key={ws.id}
                  type="button"
                  role="menuitem"
                  className="wp-item"
                  onClick={() => void choose(ws)}
                  disabled={busy}
                  title={ws.canonical_path}
                >
                  <span className="wp-itemIcon"><Folder size={16} strokeWidth={1.8} /></span>
                  <span className="wp-itemLabel">{ws.title}</span>
                  {!active && (
                    <span className="wp-itemMeta">
                      {ws.source === 'git' ? 'Git' : '本地'} · {new Date(ws.last_used_at).toLocaleDateString()}
                    </span>
                  )}
                  {active && <span className="wp-check"><Check size={16} strokeWidth={2} /></span>}
                </button>
              )
            })}
      </div>

      {showGit && (
        <div className="wp-git">
          <input
            className="wp-input"
            type="text"
            name="tm-git-url"
            autoComplete="off"
            aria-label="Git 仓库地址"
            spellCheck={false}
            placeholder="Git 地址（https://… 或 git@…）"
            value={gitUrl}
            onChange={(e) => setGitUrl(e.target.value)}
          />
          <input
            className="wp-input"
            type="text"
            name="tm-git-branch"
            autoComplete="off"
            aria-label="Git 分支（可选）"
            spellCheck={false}
            placeholder="分支（可选）"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && gitUrl.trim() && !busy) void cloneGit()
            }}
          />
          <button
            type="button"
            className="wp-gitGo"
            onClick={() => void cloneGit()}
            disabled={busy || !gitUrl.trim()}
          >
            {busy
              ? <Loader2 size={13} className="dsh-spin" strokeWidth={2} />
              : <GitFork size={13} strokeWidth={1.8} />}
            拉取并选择
          </button>
        </div>
      )}

      {error && <div className="wp-error" role="alert">{error}</div>}

      <div className="wp-footer">
        <button
          type="button"
          role="menuitem"
          className="wp-item"
          onClick={() => void pickLocal()}
          disabled={busy || !desktop}
          title={desktop ? '打开系统目录选择对话框' : '仅桌面版支持选择本地文件夹'}
        >
          <span className="wp-itemIcon"><FolderPlus size={16} strokeWidth={1.8} /></span>
          <span className="wp-itemLabel">选择文件夹</span>
        </button>
        <button
          type="button"
          role="menuitem"
          className="wp-item"
          onClick={() => setShowGit((v) => !v)}
          disabled={busy}
          aria-expanded={showGit}
        >
          <span className="wp-itemIcon"><GitFork size={16} strokeWidth={1.8} /></span>
          <span className="wp-itemLabel">从 Git 拉取</span>
          <ChevronDown size={14} strokeWidth={1.8} className={`wp-chevron${showGit ? ' is-open' : ''}`} />
        </button>
        {selectedId != null && (
          <button
            type="button"
            role="menuitem"
            className="wp-item wp-danger"
            onClick={() => void unbind()}
            disabled={busy}
            title="解除本会话与当前项目的绑定（不会删除项目登记）"
          >
            <span className="wp-itemIcon"><Unlink size={16} strokeWidth={1.8} /></span>
            <span className="wp-itemLabel">解除绑定</span>
          </button>
        )}
      </div>
    </div>,
    document.body,
  )
}
