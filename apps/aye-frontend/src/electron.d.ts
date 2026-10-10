export interface ElectronArchiveProgress {
  processedBytes: number
  totalBytes: number
  entries: number
}

export type ElectronBackendPhase =
  | 'starting'
  | 'ready'
  | 'degraded'
  | 'crashed'
  | 'stopped'
  | 'error'

export interface ElectronBackendState {
  phase: ElectronBackendPhase
  detail: string | null
}

export type ElectronUpdateStatus =
  | 'idle'
  | 'checking'
  | 'available'
  | 'not-available'
  | 'downloading'
  | 'downloaded'
  | 'error'

export interface ElectronUpdateState {
  status: ElectronUpdateStatus
  version: string | null
  progress: number
  notes: string | null
  message: string | null
}

interface ElectronAPI {
  getBackendInfo: () => Promise<{ port: number; token: string }>
  getBackendState?: () => Promise<ElectronBackendState>
  onBackendState?: (cb: (s: ElectronBackendState) => void) => () => void
  restartBackend?: () => Promise<{ ok: boolean; reason?: string; message?: string }>
  getUpdateState?: () => Promise<ElectronUpdateState>
  onUpdateState?: (cb: (s: ElectronUpdateState) => void) => () => void
  checkForUpdates?: () => Promise<{ ok: boolean; hasUpdate?: boolean; reason?: string; message?: string }>
  downloadUpdate?: () => Promise<{ ok: boolean; message?: string }>
  quitAndInstallUpdate?: () => Promise<{ ok: boolean; reason?: string }>
  grantLocalPath?: (path: string) => Promise<string>
  getPathForFile: (file: File) => string
  selectWorkspace: () => Promise<string | null>
  selectFiles: () => Promise<string[]>
  readFileRange: (path: string, start: number, end: number) => Promise<ArrayBuffer>
  statLocal: (path: string) => Promise<{ size: number; mtime: number } | null>
  archiveWorkspace: (
    dir: string,
    format: 'zip' | 'tar.gz',
    excludes: string[],
  ) => Promise<{ path: string; size: number }>
  onArchiveProgress: (cb: (p: ElectronArchiveProgress) => void) => () => void
  cleanupArchive?: (path: string) => Promise<boolean>
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI
  }
}

export {}
