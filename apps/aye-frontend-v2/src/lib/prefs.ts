/**
 * 跨刷新保留的少量 UI 偏好（localStorage）。
 *
 * 安全边界：这里只存「选择项」（主机 id / 会话 id），绝不存密码、私钥、SSH sessionId
 * 这类敏感或易失效数据——SSH 存活会话由前端启动时调 GET /ssh/active 向后端对账恢复。
 */

const KEY_LAST_HOST_ID = 'aye.lastHostId'
const KEY_LAST_SESSION_ID = 'aye.lastSessionId'

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key)
    else window.localStorage.setItem(key, value)
  } catch {
    /* 隐私模式 / 存储被禁用：偏好静默失效，不影响功能 */
  }
}

export function getLastHostId(): number | null {
  const raw = read(KEY_LAST_HOST_ID)
  if (raw === null) return null
  const n = Number(raw)
  return Number.isInteger(n) && n > 0 ? n : null
}

export function setLastHostId(id: number | null): void {
  write(KEY_LAST_HOST_ID, id === null ? null : String(id))
}

export function getLastSessionId(): string | null {
  const raw = read(KEY_LAST_SESSION_ID)
  return raw && raw.trim() !== '' ? raw : null
}

export function setLastSessionId(id: string | null): void {
  write(KEY_LAST_SESSION_ID, id)
}
