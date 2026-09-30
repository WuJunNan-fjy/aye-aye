import { contextBridge, ipcRenderer, webUtils } from 'electron'

export interface ArchiveProgress {
  processedBytes: number
  totalBytes: number
  entries: number
}

export interface ArchiveResult {
  path: string
  size: number
}

export type BackendPhase = 'starting' | 'ready' | 'degraded' | 'crashed' | 'stopped' | 'error'

export interface BackendState {
  phase: BackendPhase
  detail: string | null
}

export type UpdateStatus =
  | 'idle'
  | 'checking'
  | 'available'
  | 'not-available'
  | 'downloading'
  | 'downloaded'
  | 'error'

export interface UpdateState {
  status: UpdateStatus
  version: string | null
  progress: number
  notes: string | null
  message: string | null
}

const api = {
  getBackendInfo: () => ipcRenderer.invoke('aye:get-backend-info'),

  /** 查询后端状态机当前状态（挂载时同步一次）。 */
  getBackendState: (): Promise<BackendState> =>
    ipcRenderer.invoke('aye:get-backend-state'),

  /** 订阅后端状态变化（starting/ready/degraded/crashed/error）；返回取消订阅函数。 */
  onBackendState: (cb: (s: BackendState) => void): (() => void) => {
    const listener = (_e: unknown, s: BackendState) => cb(s)
    ipcRenderer.on('aye:backend-state', listener)
    return () => ipcRenderer.removeListener('aye:backend-state', listener)
  },

  /** 崩溃/错误后手动重试拉起后端（ready 或启动中调用会被忽略）。 */
  restartBackend: (): Promise<{ ok: boolean; reason?: string; message?: string }> =>
    ipcRenderer.invoke('aye:restart-backend'),

  // ── 应用自动更新（electron-updater / generic 更新源） ──

  /** 查询当前更新状态机（挂载时同步一次）。 */
  getUpdateState: (): Promise<UpdateState> =>
    ipcRenderer.invoke('aye:get-update-state'),

  /** 订阅更新状态（available/downloading/downloaded 等）；返回取消订阅函数。 */
  onUpdateState: (cb: (s: UpdateState) => void): (() => void) => {
    const listener = (_e: unknown, s: UpdateState) => cb(s)
    ipcRenderer.on('aye:update-state', listener)
    return () => ipcRenderer.removeListener('aye:update-state', listener)
  },

  /** 立即检查一次更新；dev 模式返回 { ok:false, reason:'dev' }。 */
  checkForUpdates: (): Promise<{ ok: boolean; hasUpdate?: boolean; reason?: string; message?: string }> =>
    ipcRenderer.invoke('aye:check-for-updates'),

  /** 用户确认后下载更新（安装包约 150MB）。 */
  downloadUpdate: (): Promise<{ ok: boolean; message?: string }> =>
    ipcRenderer.invoke('aye:download-update'),

  /** 下载完成后静默安装并重启应用。 */
  quitAndInstallUpdate: (): Promise<{ ok: boolean; reason?: string }> =>
    ipcRenderer.invoke('aye:quit-and-install-update'),

  /** 为用户确认过的本机路径申请 HMAC 来源凭证（附件/工作区注册用）。 */
  grantLocalPath: (path: string): Promise<string> =>
    ipcRenderer.invoke('aye:grant-local-path', path),
  setCredential: (account: string, password: string) =>
    ipcRenderer.invoke('aye:set-credential', account, password),
  getCredential: (account: string) =>
    ipcRenderer.invoke('aye:get-credential', account),
  deleteCredential: (account: string) =>
    ipcRenderer.invoke('aye:delete-credential', account),

  // ── 本地文件能力（最小授权面，不暴露任意 fs） ──

  /** File 对象 → 本机绝对路径（Electron 35 官方方式）。 */
  getPathForFile: (file: File): string => webUtils.getPathForFile(file),

  /** 原生选择一个目录，取消返回 null。 */
  selectWorkspace: (): Promise<string | null> =>
    ipcRenderer.invoke('aye:select-workspace'),

  /** 原生多选文件，取消返回空数组。 */
  selectFiles: (): Promise<string[]> =>
    ipcRenderer.invoke('aye:select-files'),

  /** 读取本地文件指定字节范围（归档产物分片用）。 */
  readFileRange: (path: string, start: number, end: number): Promise<ArrayBuffer> =>
    ipcRenderer.invoke('aye:read-file-range', path, start, end),

  /** 查询本地文件大小/修改时间。 */
  statLocal: (path: string): Promise<{ size: number; mtime: number } | null> =>
    ipcRenderer.invoke('aye:stat-local', path),

  /** 主进程归档目录。 */
  archiveWorkspace: (dir: string, format: 'zip' | 'tar.gz', excludes: string[]): Promise<ArchiveResult> =>
    ipcRenderer.invoke('aye:archive-workspace', dir, format, excludes),

  /** 归档进度事件订阅；返回取消订阅函数。 */
  onArchiveProgress: (cb: (p: ArchiveProgress) => void): (() => void) => {
    const listener = (_e: unknown, p: ArchiveProgress) => cb(p)
    ipcRenderer.on('aye:archive-progress', listener)
    return () => ipcRenderer.removeListener('aye:archive-progress', listener)
  },

  /** 上传完成/取消后清理归档本地产物（仅限主进程归档临时目录）。 */
  cleanupArchive: (path: string): Promise<boolean> =>
    ipcRenderer.invoke('aye:cleanup-archive', path),

  // 窗口控制
  minimizeWindow: () => ipcRenderer.invoke('aye:window-minimize'),
  maximizeWindow: () => ipcRenderer.invoke('aye:window-maximize'),
  closeWindow: () => ipcRenderer.invoke('aye:window-close'),
  isWindowMaximized: () => ipcRenderer.invoke('aye:window-is-maximized'),
}

contextBridge.exposeInMainWorld('electronAPI', api)

export type ElectronAPI = typeof api
