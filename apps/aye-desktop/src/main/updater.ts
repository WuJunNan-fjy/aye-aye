import { app, BrowserWindow, ipcMain } from 'electron'
import { autoUpdater } from 'electron-updater'

/**
 * 动态更新（electron-updater / generic provider）。
 *
 * 更新源由 electron-builder.yml 的 publish 段在打包时写入 resources/app-update.yml：
 *   启动后延迟首检 → GET <feed>/latest.yml 比对版本 → 有新版时通知渲染层弹徽标；
 *   用户确认后才下载（autoDownload=false，安装包约 150MB，不偷跑流量），
 *   下载完成由用户点击「重启安装」，走 quitAndInstall 静默覆盖安装。
 * 检查失败（断网/更新源未就绪/占位地址）只静默记日志，不打扰用户。
 */

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
  /** 远端版本号，如 0.0.2 */
  version: string | null
  /** 下载进度 0~100 */
  progress: number
  /** latest.yml 中的发布说明（纯文本，可能为空） */
  notes: string | null
  /** error 状态下的诊断信息（仅日志/调试用，UI 不直接展示） */
  message: string | null
}

const UPDATE_CHANNEL = 'aye:update-state'
/** 避开 JVM 冷启动与首屏请求，启动 15s 后再首检 */
const FIRST_CHECK_DELAY_MS = 15_000
/** 驻留期间每 4 小时复检一次（关窗驻留托盘时也能发现新版） */
const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000

let state: UpdateState = {
  status: 'idle',
  version: null,
  progress: 0,
  notes: null,
  message: null,
}
let initialized = false
/** 用户已确认安装：before-quit 收尾后必须走正常 app.quit()，让 electron-updater 在 quit 事件拉起安装包 */
let updateInstallPending = false

export function isUpdateInstallPending(): boolean {
  return updateInstallPending
}

export function initAutoUpdater(
  getMainWindow: () => BrowserWindow | null,
  onUpdateAvailable?: (version: string) => void,
): void {
  if (initialized) return
  initialized = true

  const setState = (patch: Partial<UpdateState>): void => {
    state = { ...state, ...patch }
    const win = getMainWindow()
    if (win && !win.isDestroyed()) {
      win.webContents.send(UPDATE_CHANNEL, state)
    }
  }

  // IPC 无论是否打包都注册，避免渲染端 invoke 悬空；dev 下检查直接返回不可用
  ipcMain.handle('aye:get-update-state', () => state)

  ipcMain.handle('aye:check-for-updates', async () => {
    if (!app.isPackaged) return { ok: false, reason: 'dev' as const }
    try {
      setState({ status: 'checking', message: null })
      const result = await autoUpdater.checkForUpdates()
      return { ok: true, hasUpdate: !!result?.updateInfo }
    } catch (e) {
      setState({ status: 'error', message: e instanceof Error ? e.message : String(e) })
      return { ok: false, message: e instanceof Error ? e.message : String(e) }
    }
  })

  ipcMain.handle('aye:download-update', async () => {
    try {
      setState({ status: 'downloading', progress: 0 })
      await autoUpdater.downloadUpdate()
      return { ok: true }
    } catch (e) {
      setState({ status: 'available', progress: 0, message: e instanceof Error ? e.message : String(e) })
      return { ok: false, message: e instanceof Error ? e.message : String(e) }
    }
  })

  ipcMain.handle('aye:quit-and-install-update', () => {
    if (state.status !== 'downloaded') return { ok: false, reason: 'not-downloaded' as const }
    updateInstallPending = true
    // isSilent=true 走 NSIS /S 静默安装；isForceRunAfter=true 装完自动重启应用
    setImmediate(() => autoUpdater.quitAndInstall(true, true))
    return { ok: true }
  })

  if (!app.isPackaged) return

  autoUpdater.logger = console
  autoUpdater.autoDownload = false
  // 只在用户显式点击时安装，避免普通退托盘/退出时突然拉起安装程序
  autoUpdater.autoInstallOnAppQuit = false

  autoUpdater.on('checking-for-update', () => {
    setState({ status: 'checking', message: null })
  })

  autoUpdater.on('update-available', (info) => {
    const notes = typeof info.releaseNotes === 'string' ? info.releaseNotes : null
    setState({ status: 'available', version: info.version, progress: 0, notes, message: null })
    try { onUpdateAvailable?.(info.version) } catch { /* 通知失败不影响流程 */ }
  })

  autoUpdater.on('update-not-available', () => {
    setState({ status: 'not-available', progress: 0, message: null })
  })

  autoUpdater.on('download-progress', (p) => {
    // 事件可能乱序到达，只有处于下载态时刷新
    if (state.status === 'downloading') {
      setState({ progress: Math.max(0, Math.min(100, p.percent || 0)) })
    }
  })

  autoUpdater.on('update-downloaded', (info) => {
    const notes = typeof info.releaseNotes === 'string' ? info.releaseNotes : state.notes
    setState({ status: 'downloaded', version: info.version, progress: 100, notes, message: null })
  })

  autoUpdater.on('error', (err) => {
    // 检查阶段网络错误保持静默（不覆盖 available/downloaded 等可交互状态）
    console.warn('[updater]', err?.message || err)
    if (state.status === 'checking' || state.status === 'downloading') {
      setState({ status: 'error', message: err?.message || String(err) })
    }
  })

  const checkQuietly = (): void => {
    autoUpdater.checkForUpdates().catch((e) => {
      // 更新源不可达（占位地址/断网）属预期，不打扰用户
      console.warn('[updater] 检查更新失败：', e?.message || e)
    })
  }
  setTimeout(checkQuietly, FIRST_CHECK_DELAY_MS)
  setInterval(checkQuietly, CHECK_INTERVAL_MS)
}
