import request from '@/api/request'

export interface ConnectCredentials {
  password?: string
  privateKey?: string
  passphrase?: string
}

export interface ExecResult {
  exitCode: number
  stdout: string
  stderr: string
  durationMs: number
}

/** 连接结果：connected 直接有 sessionId；need_trust 需先确认主机指纹再重连 */
export interface ConnectResult {
  status: 'connected' | 'need_trust'
  sessionId?: string
  /** NEW=首连 / CHANGED=指纹已变化（可能中间人） */
  trustReason?: 'NEW' | 'CHANGED'
  fingerprint?: string
  address?: string
  port?: number
}

/** 后端仍存活的 SSH 会话（GET /ssh/active 对账用）。 */
export interface ActiveSshSession {
  sessionId: string
  hostId: number
  hostName: string
}

export default {
  connect: (hostId: number, credentials: ConnectCredentials) =>
    request.post(`/ssh/connect/${hostId}`, credentials) as Promise<ConnectResult>,
  exec: (sessionId: string, command: string, timeout = 30) =>
    request.post(`/ssh/exec/${sessionId}`, { command, timeout }) as Promise<ExecResult>,
  disconnect: (sessionId: string) =>
    request.post(`/ssh/disconnect/${sessionId}`, {}) as Promise<{ status: string }>,
  /** 查询后端仍存活的会话（刷新后对账恢复连接态，无需重新输密码） */
  activeSessions: () =>
    request.get('/ssh/active') as Promise<{ sessions: ActiveSshSession[] }>,
  /** TOFU：确认信任某主机指纹 */
  trust: (address: string, port: number, fingerprint: string) =>
    request.post('/ssh/trust', { address, port, fingerprint }) as Promise<{ trusted: boolean }>,
  /** 撤销已信任指纹 */
  untrust: (address: string, port: number) =>
    request.post('/ssh/untrust', { address, port }) as Promise<{ untrusted: boolean }>,
  /** 已信任指纹列表 */
  knownHosts: () => request.get('/ssh/known-hosts') as Promise<Array<Record<string, unknown>>>,
}