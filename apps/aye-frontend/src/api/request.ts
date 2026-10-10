import axios, { type AxiosInstance } from 'axios'

/**
 * v2 数据层统一入口。
 *
 * - 浏览器模式：直连当前页面主机名的 8080 端口（局域网访问自动指向服务端 LAN IP）；
 * - Electron 桌面模式：经 preload IPC 获取后端随机端口与一次性 API 令牌，
 *   REST 请求带 X-API-Token，后端仅监听 127.0.0.1。
 */

/** 浏览器模式访问页面的主机名 */
const HOST = typeof window !== 'undefined' ? window.location.hostname : '127.0.0.1'

interface BackendInfo {
  port: number
  token: string
}

let infoPromise: Promise<BackendInfo> | null = null

/** 已见过的首次 ready（启动即就绪），用于只在“崩溃重启后的 ready”刷新连接信息。 */
let firstReadySeen = false

async function resolveBackendInfo(): Promise<BackendInfo> {
  const eapi = (window as unknown as {
    electronAPI?: { getBackendInfo?: () => Promise<BackendInfo> }
  }).electronAPI
  if (eapi?.getBackendInfo) {
    try {
      return await eapi.getBackendInfo()
    } catch {
      /* 回退浏览器形态 */
    }
  }
  return { port: 8080, token: '' }
}

/** 获取（并缓存）后端端口/令牌。 */
export async function getBackendInfo(): Promise<BackendInfo> {
  if (!infoPromise) infoPromise = resolveBackendInfo()
  return infoPromise
}

/** 当前是否运行在 Electron 桌面壳内。 */
export function isDesktopRuntime(): boolean {
  return !!(window as unknown as { electronAPI?: unknown }).electronAPI
}

/** REST baseURL（ensureBackend 完成前为浏览器形态的默认值）。 */
export let API_BASE = `http://${HOST}:8080/api`

/** WebSocket baseURL（不含路径部分），终端走 /ws/terminal/{sessionId}。 */
export let WS_BASE = `ws://${HOST}:8080/ws`

/**
 * 应用启动时调用一次：解析桌面端后端信息并固化 baseURL。
 * 桌面模式固定连 127.0.0.1，浏览器模式跟随页面主机名。
 */
export async function ensureBackend(): Promise<BackendInfo> {
  const info = await getBackendInfo()
  const host = isDesktopRuntime() ? '127.0.0.1' : HOST
  API_BASE = `http://${host}:${info.port || 8080}/api`
  WS_BASE = `ws://${host}:${info.port || 8080}/ws`
  return info
}

/**
 * 订阅桌面端后端状态：崩溃自动重启后端口会变化，丢弃缓存的 backend info 并重新
 * 解析 baseURL/令牌，避免渲染端永久打向旧端口（表现为重启后所有操作无响应）。
 * 首次启动的 ready 忽略；仅重启后的 ready 触发刷新，并广播事件供页面按需重载数据。
 */
export function watchBackendRestart(): void {
  const eapi = (window as unknown as {
    electronAPI?: {
      onBackendState?: (cb: (s: { phase: string }) => void) => (() => void) | void
      getBackendState?: () => Promise<{ phase: string }>
    }
  }).electronAPI
  if (!eapi?.onBackendState) return
  // 启动的 ready 事件可能在页面 JS 执行前就已发出（主进程先 ready 再建窗），
  // 主动读一次当前相位：若已是 ready，则把它计为“首次”，避免后续误刷新。
  eapi.getBackendState?.()
    .then((s) => { if (s?.phase === 'ready') firstReadySeen = true })
    .catch(() => { /* ignore */ })
  eapi.onBackendState((s) => {
    if (s.phase !== 'ready') return
    if (!firstReadySeen) {
      firstReadySeen = true
      return
    }
    infoPromise = null
    void ensureBackend()
      .then(() => window.dispatchEvent(new CustomEvent('aye:backend-restarted')))
      .catch(() => { /* 保留下次重试；徽标仍显示断开态 */ })
  })
}

/** 裸 fetch 使用的鉴权头（桌面端带令牌，浏览器模式为空对象）。 */
export async function authHeaders(
  base?: Record<string, string>,
): Promise<Record<string, string>> {
  const { token } = await getBackendInfo()
  return token ? { ...(base || {}), 'X-API-Token': token } : { ...(base || {}) }
}

async function getWsBase(): Promise<string> {
  await getBackendInfo()
  return WS_BASE
}

export { getWsBase }

/** SFTP 文件下载直链（令牌经 fetch 头传递，见 sftpApi）。 */
export function buildDownloadUrl(sessionId: string, path: string): string {
  return `${API_BASE}/sftp/download/${sessionId}?path=${encodeURIComponent(path)}`
}

const request = axios.create({
  baseURL: API_BASE,
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

/** 判断请求目标是否为本机后端（避免把令牌发给第三方 origin）。 */
function isOwnBackend(configUrl: string | undefined): boolean {
  if (!configUrl) return true
  if (!/^https?:\/\//.test(configUrl)) return true // 相对路径交由 baseURL
  try {
    const u = new URL(configUrl)
    return u.hostname === '127.0.0.1' || u.hostname === 'localhost' || u.hostname === HOST
  } catch {
    return false
  }
}

/** 给 axios 实例安装动态 baseURL + 令牌拦截器（含全局 axios）。 */
export function installAuthInterceptor(instance: AxiosInstance): void {
  instance.interceptors.request.use(async (config) => {
    if (!isOwnBackend(config.url)) return config
    const info = await ensureBackend()
    config.baseURL = API_BASE
    if (info.token) config.headers['X-API-Token'] = info.token
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type']
    }
    return config
  })
}

// 本模块实例 + 全局 axios（transferApi 等直接用裸 axios 的调用点）
installAuthInterceptor(request)
installAuthInterceptor(axios as unknown as AxiosInstance)

request.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const data = err.response?.data
    if (data?.message) {
      const error = new Error(data.message)
      ;(error as any).response = err.response
      ;(error as any).code = data.code
      return Promise.reject(error)
    }
    return Promise.reject(data?.error || err.message)
  }
)

export default request
