import React, { useState, useEffect, useRef, useMemo, forwardRef, useImperativeHandle, memo } from 'react'
import { message } from 'antd'
import {
  RotateCw,
  ArrowUp,
  Home,
  Upload,
  Download,
  Trash2,
  Copy,
  Search,
  FolderOpen,
  Pencil,
  MoveRight,
} from 'lucide-react'
import sftpApi, { type SftpFile } from '@/api/sftpApi'
import { useHostStore, selectSessionId, selectCurrentHost } from '@/store/useHostStore'
import { getFileIcon } from '@/utils/fileIcons'
import { DRAG_FILE_MIME } from '@/components/chat/InputBar'
import ConnectCard from '@/components/servers/ConnectCard'
import FileEditorModal from './FileEditorModal'
import { useUploadStore } from '@/store/useUploadStore'
import { archiveAndEnqueue } from '@/lib/archiveUpload'
import { readDroppedEntries, collectDirectoryFiles } from '@/lib/dropEntries'
import '@/styles/files.css'

interface TreeNode extends SftpFile {
  key: string
}

export interface ServerFileTreeRef {
  refresh: () => void
  goHome: () => void
  goUp: () => void
  navigateTo: (path: string) => void
  getCurrentPath: () => string
  /**
   * 在树中定位远程文件/目录：导航到其父目录并高亮该行。
   * @returns true=已发起定位；false=当前未连接主机（调用方应提示先连接）
   */
  revealFile: (path: string) => boolean
  /** 触发隐藏文件输入框，上传文件到当前远程目录。 */
  upload: () => void
  /** 触发目录选择输入框，整目录原样上传。 */
  uploadFolder: () => void
  /** 选择本地工作区，归档后上传（仅桌面端）。 */
  archiveUpload: () => void
  /** 在当前目录新建文件夹。 */
  mkdirHere: () => void
}

// memo：文件列表可能上百行，AI 流式期间父页面每帧重渲染与文件树无关，靠 memo 挡住。
const ServerFileTree = memo(forwardRef<ServerFileTreeRef, { hideHeader?: boolean }>(({ hideHeader = false }, ref) => {
  const sessionId = useHostStore(selectSessionId)
  const currentHost = useHostStore(selectCurrentHost)

  const [fileList, setFileList] = useState<TreeNode[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [currentPath, setCurrentPath] = useState('/')

  const [selectedPath, setSelectedPath] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [sortKey, setSortKey] = useState<'name' | 'size' | 'time'>('name')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('asc')

  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; file: TreeNode } | null>(null)
  const listCacheRef = useRef<Map<string, TreeNode[]>>(new Map())
  const fileInputRef = useRef<HTMLInputElement>(null)
  const dirInputRef = useRef<HTMLInputElement>(null)
  const loadingPathRef = useRef<string | null>(null)

  const handleFilesDrop = async (files: File[]): Promise<void> => {
    if (!sessionId) return
    const withDir = files.filter((f) => f.webkitRelativePath && f.webkitRelativePath.includes('/'))
    if (withDir.length === files.length && files.length > 0) {
      const tops = new Set(files.map((f) => f.webkitRelativePath.split('/')[0]))
      if (tops.size === 1) {
        useUploadStore.getState().enqueue({
          kind: 'directory', sessionId, remoteDir: currentPath, files,
        })
        return
      }
    }
    for (const f of files) {
      useUploadStore.getState().enqueue({
        kind: 'file', sessionId, remoteDir: currentPath, file: f,
      })
    }
  }

  /**
   * OS 拖入文件面板：必须经 entries API 处理——dataTransfer.files 对拖入的文件夹
   * 不提供 webkitRelativePath，直接用会把目录内文件平铺、丢掉结构。
   * 目录条目递归收集后按 directory 任务入队；散文件走 handleFilesDrop。
   */
  const handleDropEvent = async (e: React.DragEvent<HTMLDivElement>): Promise<void> => {
    if (!sessionId) return
    try {
      const entries = await readDroppedEntries(e)
      for (const d of entries.filter((x) => x.kind === 'dir')) {
        if (d.entry?.isDirectory) {
          const files = await collectDirectoryFiles(
            d.entry as FileSystemDirectoryEntry, d.file.name)
          if (files.length > 0) {
            useUploadStore.getState().enqueue({
              kind: 'directory', sessionId, remoteDir: currentPath, files,
            })
          }
        }
      }
      const loose = entries.filter((x) => x.kind === 'file').map((x) => x.file)
      if (loose.length > 0) await handleFilesDrop(loose)
    } catch (err) {
      // entries/递归读取可能因权限或浏览器实现失败，不能让拖放静默无响应
      message.error(`读取拖入内容失败：${err instanceof Error ? err.message : err}`)
    }
  }

  /** revealFile 跨目录定位：目标目录列表加载完后待高亮的完整路径 */
  const pendingSelectRef = useRef<string | null>(null)

  const [editorFile, setEditorFile] = useState<{ path: string; name: string } | null>(null)
  const [uploading, setUploading] = useState(false)
  /** 树内拖放移动：当前高亮的目标目录行 key */
  const [dragOverDir, setDragOverDir] = useState('')

  // 从后端返回中构造树节点（补齐 key）
  const toNodes = (files: SftpFile[], base: string): TreeNode[] =>
    files.map((f) => ({
      ...f,
      key: f.path || `${base.replace(/\/+$/, '')}/${f.name}`,
    }))

  const loadList = async (path: string, force = false) => {
    if (!sessionId) return
    const cacheKey = `${sessionId}:${path}`
    const cached = listCacheRef.current.get(cacheKey)
    if (cached && !force) {
      setFileList(cached)
      return
    }
    loadingPathRef.current = cacheKey
    setLoading(true)
    setError(null)
    try {
      const res = await sftpApi.list(sessionId, path)
      const nodes = toNodes(res, path)
      listCacheRef.current.set(cacheKey, nodes)
      setFileList(nodes)
      // revealFile 的待高亮目标：一次性消费，命中则选中，未命中（已删除/无权限）也清除
      const want = pendingSelectRef.current
      if (want) {
        pendingSelectRef.current = null
        const hit = nodes.find((n) => (n.path || n.key) === want)
        if (hit) setSelectedPath(hit.key)
      }
    } catch (e: any) {
      pendingSelectRef.current = null
      setError(`读取目录失败：${e?.message || e}`)
      setFileList([])
    } finally {
      setLoading(false)
      loadingPathRef.current = null
    }
  }

  // session 或初始路径变化 → 加载
  useEffect(() => {
    setCurrentPath('/')
    setFileList([])
    setSelectedPath('')
    pendingSelectRef.current = null
    if (sessionId) loadList('/', true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // 同步当前目录到上传 store，供聊天拖文件夹「上传到服务器」取目标
  useEffect(() => {
    useUploadStore.getState().setTreeCurrentPath(currentPath)
  }, [currentPath])

  const navigateTo = (path: string) => {
    setCurrentPath(path)
    setSelectedPath('')
    loadList(path)
  }

  const refresh = () => {
    if (sessionId) loadList(currentPath, true)
  }

  const goHome = () => navigateTo('/')
  const goUp = () => {
    const parent = currentPath === '/' ? '/' : currentPath.replace(/\/[^/]*$/, '') || '/'
    navigateTo(parent)
  }

  /** 对话侧「在文件树定位」：跳到父目录并高亮目标（强制拉取最新列表）。 */
  const revealFile = (targetPath: string): boolean => {
    if (!sessionId) return false
    const clean = targetPath.trim().replace(/\/+$/, '')
    if (!clean) return false
    if (clean === '/') {
      goHome()
      return true
    }
    const slash = clean.lastIndexOf('/')
    const parent = slash <= 0 ? '/' : clean.slice(0, slash)
    pendingSelectRef.current = clean
    setSearchQuery('')
    if (parent === currentPath) {
      setSelectedPath(clean)
      loadList(parent, true)
    } else {
      setCurrentPath(parent)
      setSelectedPath('')
      loadList(parent, true)
    }
    return true
  }

  useImperativeHandle(ref, () => ({
    refresh,
    goHome,
    goUp,
    navigateTo,
    getCurrentPath: () => currentPath,
    revealFile,
    upload: () => fileInputRef.current?.click(),
    uploadFolder: () => dirInputRef.current?.click(),
    archiveUpload: () => void archiveAndEnqueue(sessionId!, currentPath),
    mkdirHere: handleMkdirHere,
  }))

  // 面包屑（隐藏点开头项）
  const breadcrumbs = useMemo(() => {
    const parts = currentPath.split('/').filter(Boolean)
    const crumbs: { name: string; path: string; isRoot?: boolean; isHidden?: boolean }[] = [
      { name: '/', path: '/', isRoot: true },
    ]
    let path = ''
    for (const part of parts) {
      path += '/' + part
      crumbs.push({ name: part, path, isHidden: part.startsWith('.') })
    }
    return crumbs
  }, [currentPath])

  const sortedList = useMemo(() => {
    const dirs = fileList.filter((f) => f.type === 'directory')
    const files = fileList.filter((f) => f.type === 'file')
    const sortFn = (a: TreeNode, b: TreeNode) => {
      let cmp = 0
      if (sortKey === 'name') cmp = a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
      else if (sortKey === 'size') cmp = (a.size || 0) - (b.size || 0)
      else cmp = (a.modifiedTime || 0) - (b.modifiedTime || 0)
      return sortOrder === 'asc' ? cmp : -cmp
    }
    return [...dirs.sort(sortFn), ...files.sort(sortFn)]
  }, [fileList, sortKey, sortOrder])

  const filteredList = useMemo(() => {
    if (!searchQuery.trim()) return sortedList
    const q = searchQuery.trim().toLowerCase()
    return sortedList.filter((f) => f.name.toLowerCase().includes(q))
  }, [sortedList, searchQuery])

  const totalSizeText = useMemo(() => {
    const total = fileList.reduce((sum, f) => sum + (f.size || 0), 0)
    return formatSize(total)
  }, [fileList])

  const toggleSort = (key: 'name' | 'size' | 'time') => {
    if (sortKey === key) {
      setSortOrder((o) => (o === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortOrder('asc')
    }
  }

  const handleOpen = (node: TreeNode) => {
    if (node.type === 'directory') {
      navigateTo(node.path || node.key)
    } else {
      setEditorFile({ path: node.path || node.key, name: node.name })
    }
  }

  const handleDownload = async (node: TreeNode) => {
    if (!sessionId) return
    try {
      await sftpApi.download(sessionId, node.path || node.key)
    } catch (e: any) {
      message.error(`下载失败：${e?.message || e}`)
    }
  }

  const handleDelete = async (node: TreeNode) => {
    if (!sessionId) return
    const isDir = node.type === 'directory'
    if (!window.confirm(`确认删除${isDir ? '目录' : '文件'} ${node.name}？此操作不可撤销。`)) return
    try {
      await sftpApi.remove(sessionId, node.path || node.key, isDir)
      message.success(`已删除 ${node.name}`)
      refresh()
    } catch (e: any) {
      message.error(`删除失败：${e?.message || e}`)
    }
  }

  const handleUpload = async (file: File) => {
    if (!sessionId) return
    setUploading(true)
    try {
      await sftpApi.upload(sessionId, currentPath, file)
      message.success(`已上传 ${file.name}`)
      refresh()
    } catch (e: any) {
      message.error(`上传失败：${e?.message || e}`)
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const copyPath = async (node: TreeNode) => {
    const p = node.path || node.key
    try {
      await navigator.clipboard.writeText(p)
      message.success('已复制路径')
    } catch {
      message.error('复制失败')
    }
  }

  /** 新建文件夹（当前目录下）。 */
  const handleMkdirHere = (): void => {
    if (!sessionId) return
    const input = window.prompt('新建文件夹（名称）：')
    if (!input || !input.trim()) return
    const clean = input.trim().replace(/[\\/:*?"<>|]/g, '_')
    const target = `${currentPath.replace(/\/+$/, '')}/${clean}`
    void (async () => {
      try {
        await sftpApi.mkdir(sessionId, target)
        message.success(`已新建 ${clean}`)
        listCacheRef.current.delete(`${sessionId}:${currentPath}`)
        refresh()
      } catch (e: any) {
        message.error(`新建失败：${e?.message || e}`)
      }
    })()
  }

  /** 重命名（同目录改名）。 */
  const handleRename = (node: TreeNode): void => {
    if (!sessionId) return
    const input = window.prompt('重命名为：', node.name)
    if (!input || !input.trim() || input.trim() === node.name) return
    // 只允许单段名称：分隔符/盘符等一律替换，避免 ../ 逃逸或意外移动到其他目录
    const clean = input.trim().replace(/[\\/:*?"<>|]/g, '_')
    if (clean === '.' || clean === '..') {
      message.warning('名称不合法')
      return
    }
    const parent = (node.path || node.key).replace(/\/[^/]*$/, '') || '/'
    const target = `${parent}/${clean}`
    void doRename(sessionId, node.path || node.key, target, node.name)
  }

  /** 移动到指定绝对目录。 */
  const handleMove = (node: TreeNode): void => {
    if (!sessionId) return
    const input = window.prompt(`移动「${node.name}」到目标目录（绝对路径）：`)
    if (!input || !input.trim()) return
    const destDir = input.trim().replace(/\/+$/, '')
    const target = `${destDir}/${node.name}`
    void doRename(sessionId, node.path || node.key, target, node.name)
  }

  /** 统一执行 rename（重命名与移动共用；成功后清缓存刷新）。 */
  async function doRename(sid: string, oldPath: string, newPath: string, label: string): Promise<void> {
    try {
      await sftpApi.rename(sid, oldPath, newPath)
      message.success(`已移动 ${label}`)
      listCacheRef.current.clear()
      refresh()
    } catch (e: any) {
      message.error(`操作失败：${e?.message || e}`)
    }
  }

  if (!sessionId) {
    return (
      <div className="files-empty">
        <ConnectCard compact />
      </div>
    )
  }

  return (
    <div
      className="server-file-tree"
      onDragOver={(e) => {
        if (e.dataTransfer.types?.includes('Files')) {
          e.preventDefault()
          e.dataTransfer.dropEffect = 'copy'
        }
      }}
      onDrop={(e) => {
        e.preventDefault()
        void handleDropEvent(e)
      }}
    >
      <input
        ref={fileInputRef}
        type="file"
        multiple
        style={{ display: 'none' }}
        onChange={(e) => {
          if (e.target.files?.length) handleFilesDrop(Array.from(e.target.files))
          e.target.value = ''
        }}
      />
      <input
        ref={dirInputRef}
        type="file"
        // @ts-expect-error 非标准但 Chromium 系（Electron）均支持
        webkitdirectory=""
        directory=""
        multiple
        style={{ display: 'none' }}
        onChange={(e) => {
          if (e.target.files?.length) handleFilesDrop(Array.from(e.target.files))
          e.target.value = ''
        }}
      />
      {!hideHeader && (
        <div className="files-toolbar">
          <div className="files-toolbar-left">
            <button className="dsh-icon-btn" title="刷新" onClick={refresh} disabled={loading}>
              <RotateCw className={loading ? 'dsh-spin' : undefined} />
            </button>
            <button className="dsh-icon-btn" title="上级目录" onClick={goUp} disabled={currentPath === '/'}>
              <ArrowUp />
            </button>
            <button className="dsh-icon-btn" title="根目录" onClick={goHome}>
              <Home />
            </button>
            <div className="files-breadcrumb">
              {breadcrumbs.map((crumb, i) => (
                <React.Fragment key={crumb.path}>
                  {i > 0 && <span className="files-breadcrumb-sep">/</span>}
                  <button
                    className={`files-crumb ${crumb.isHidden ? 'hidden' : ''} ${i === breadcrumbs.length - 1 ? 'current' : ''}`}
                    onClick={() => !(i === breadcrumbs.length - 1) && navigateTo(crumb.path)}
                    title={crumb.path}
                  >
                    {crumb.name}
                  </button>
                </React.Fragment>
              ))}
            </div>
          </div>
          <div className="files-toolbar-right">
            <div className="files-sort">
              {(['name', 'size', 'time'] as const).map((k) => (
                <button
                  key={k}
                  className={`files-sort-btn ${sortKey === k ? 'active' : ''}`}
                  onClick={() => toggleSort(k)}
                >
                  {k === 'name' ? '名称' : k === 'size' ? '大小' : '时间'}
                  {sortKey === k && (sortOrder === 'asc' ? ' ↑' : ' ↓')}
                </button>
              ))}
            </div>
            <div className="files-search">
              <Search />
              <input
                className="files-search-input"
                placeholder="搜索"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            <button className="dsh-btn dsh-btn-ghost dsh-btn-sm" onClick={() => fileInputRef.current?.click()} disabled={uploading}>
              <Upload /> {uploading ? '上传中…' : '上传'}
            </button>
          </div>
        </div>
      )}

      {error ? (
        <div className="files-body-error">
          <div className="empty-title">加载失败</div>
          <div className="empty-desc">{error}</div>
          <button className="dsh-btn dsh-btn-outline" onClick={refresh}>重试</button>
        </div>
      ) : (
        <div className="files-list">
          {loading && fileList.length === 0 ? (
            <div className="files-loading">加载中…</div>
          ) : filteredList.length === 0 ? (
            <div className="files-body-empty">
              {searchQuery ? '无匹配项' : '空目录'}
              {!searchQuery && currentHost && <span className="files-cwd"> · {currentPath}</span>}
            </div>
          ) : (
            filteredList.map((node) => {
              const fi = getFileIcon(node.name, node.type === 'directory')
              const isSelected = selectedPath === node.key
              const isHidden = node.name.startsWith('.')
              return (
                <div
                  key={node.key}
                  className={`files-row ${isSelected ? 'selected' : ''} ${dragOverDir === node.key ? 'files-row-drop' : ''}`}
                  draggable
                  onDragStart={(e) => {
                    e.dataTransfer.setData(DRAG_FILE_MIME, JSON.stringify({
                      path: node.path || node.key,
                      name: node.name,
                      type: node.type === 'directory' ? 'directory' : 'file',
                    }))
                    e.dataTransfer.effectAllowed = 'copy'
                  }}
                  onDragOver={node.type === 'directory' ? (e: React.DragEvent) => {
                    if (!e.dataTransfer.types?.includes(DRAG_FILE_MIME)) return
                    e.preventDefault()
                    e.stopPropagation()
                    e.dataTransfer.dropEffect = 'move'
                    setDragOverDir(node.key)
                  } : undefined}
                  onDragLeave={node.type === 'directory'
                    ? () => setDragOverDir((v) => (v === node.key ? '' : v))
                    : undefined}
                  onDrop={node.type === 'directory' ? (e: React.DragEvent) => {
                    const payload = e.dataTransfer.getData(DRAG_FILE_MIME)
                    if (!payload) return
                    e.preventDefault()
                    e.stopPropagation()
                    setDragOverDir('')
                    try {
                      const info = JSON.parse(payload) as { path?: string; name?: string }
                      const destDir = node.path || node.key
                      if (!info.path || info.path === destDir) return
                      if (destDir.startsWith(info.path + '/')) {
                        message.error('不能移动到自身或其子目录内')
                        return
                      }
                      const target = `${destDir}/${info.name}`
                      if (!window.confirm(`确认移动 ${info.name} 到 ${destDir} ？`)) return
                      void doRename(sessionId!, info.path, target, info.name ?? info.path)
                    } catch { /* 忽略无效载荷 */ }
                  } : undefined}
                  onClick={() => setSelectedPath(node.key)}
                  onDoubleClick={() => handleOpen(node)}
                  onContextMenu={(e) => {
                    e.preventDefault()
                    setContextMenu({ x: e.clientX, y: e.clientY, file: node })
                  }}
                >
                  <span className="files-row-icon" style={{ color: fi.color }}>
                    {fi.icon}
                  </span>
                  <span className={`files-row-name ${isHidden ? 'hidden' : ''}`} title={node.name}>
                    {node.name}
                  </span>
                  <span className="files-row-size">{node.type === 'file' ? formatSize(node.size) : ''}</span>
                  <span className="files-row-time">{formatTime(node.modifiedTime)}</span>
                  {node.type === 'directory' && (
                    <span className="files-row-enter"><FolderOpen /></span>
                  )}
                </div>
              )
            })
          )}
        </div>
      )}

      <div className="files-footer">
        <span className="files-count">{filteredList.length} 项</span>
        <span className="files-size">{totalSizeText}</span>
        <span className="files-cwd" title={currentPath}>{currentPath}</span>
      </div>

      {/* 右键菜单 */}
      {contextMenu && (
        <div
          className="files-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(e) => e.stopPropagation()}
          onContextMenu={(e) => e.preventDefault()}
        >
          <button
            className="files-ctx-item"
            onClick={() => {
              handleOpen(contextMenu.file)
              setContextMenu(null)
            }}
          >
            <FolderOpen /> {contextMenu.file.type === 'directory' ? '打开' : '查看'}
          </button>
          <button className="files-ctx-item" onClick={() => { handleDownload(contextMenu.file); setContextMenu(null) }}>
            <Download /> 下载
          </button>
          <button className="files-ctx-item" onClick={() => { handleRename(contextMenu.file); setContextMenu(null) }}>
            <Pencil /> 重命名
          </button>
          <button className="files-ctx-item" onClick={() => { handleMove(contextMenu.file); setContextMenu(null) }}>
            <MoveRight /> 移动到…
          </button>
          <button className="files-ctx-item" onClick={() => { copyPath(contextMenu.file); setContextMenu(null) }}>
            <Copy /> 复制路径
          </button>
          <div className="files-ctx-sep" />
          <button className="files-ctx-item danger" onClick={() => { handleDelete(contextMenu.file); setContextMenu(null) }}>
            <Trash2 /> 删除
          </button>
        </div>
      )}

      {/* 点击空白关闭右键菜单 */}
      {contextMenu && (
        <div className="files-context-backdrop" onClick={() => setContextMenu(null)} onContextMenu={(e) => { e.preventDefault(); setContextMenu(null) }} />
      )}

      <FileEditorModal
        sessionId={sessionId}
        file={editorFile}
        onClose={() => setEditorFile(null)}
        onSaved={refresh}
      />
    </div>
  )
}))

ServerFileTree.displayName = 'ServerFileTree'

function formatSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(ts: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default ServerFileTree
