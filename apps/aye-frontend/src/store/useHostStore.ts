import { create } from 'zustand'
import hostApi, { type Host } from '@/api/hostApi'
import sshApi, { type ConnectCredentials } from '@/api/sshApi'
import { getLastHostId, setLastHostId } from '@/lib/prefs'

/**
 * 服务器/连接状态 store（v2 精简版）。
 * 只保留主机列表 + 每台服务器的连接状态（sessionId/status），
 * 聊天消息/日志/分析等业务数据不进这里（对话页另有自己的会话流）。
 */

export type ServerStatus = 'connected' | 'connecting' | 'disconnected' | 'error'

/** need_trust 待确认的主机指纹信息 */
export interface PendingTrust {
  fingerprint: string
  address: string
  port: number
  /** NEW=首连 / CHANGED=指纹已变化 */
  reason?: 'NEW' | 'CHANGED'
}

export interface ServerUIState {
  status: ServerStatus
  sessionId: string | null
  /** 最近一次连接失败文案（status=error 时展示） */
  error?: string
  /** 主机密钥 TOFU 待确认指纹（status=error 且 pendingTrust 存在时，UI 应弹确认框） */
  pendingTrust?: PendingTrust
}

/** 连接结果三态：connected / need_trust / error */
export type ConnectOutcome = 'connected' | 'need_trust' | 'error'

export interface ConnectOutcomeInfo {
  status: ConnectOutcome
  pendingTrust?: PendingTrust
}

function formatConnectError(e: any): string {
  if (e?.response?.data?.message) return e.response.data.message
  if (e?.response?.data && typeof e.response.data === 'string') return e.response.data
  if (e?.message) return e.message
  return String(e)
}

/**
 * 凭据内存缓存（仅当前浏览器会话，绝不写 localStorage）。
 * 作用：在「服务器页输完密码 → 切到对话页」「终端意外断开自动重连」等场景免重输；
 * 后端 SSH 会话本身可存活 30 分钟，刷新页面后通过 GET /ssh/active 对账即可认领，
 * 无需密码；只有后端会话已失效时才需要重新输入。
 */
const credentialCache = new Map<number, ConnectCredentials>()

export function getCachedCredentials(hostId: number): ConnectCredentials | undefined {
  return credentialCache.get(hostId)
}

interface HostState {
  hosts: Host[]
  currentHostId: number | null
  serverStates: Record<number, ServerUIState>
  loaded: boolean

  ensureState: (hostId: number) => ServerUIState
  loadHosts: () => Promise<void>
  /**
   * 启动/重新可见时与后端对账：拉取存活 SSH 会话，把前端连接态恢复为真实状态。
   * 刷新页面后无需重输密码即可继续用终端；后端已失效的会话标记为未连接。
   */
  reconcile: () => Promise<void>
  addHost: (host: Host) => Promise<Host>
  updateHost: (id: number, host: Host) => Promise<Host>
  removeHost: (id: number) => Promise<void>
  selectHost: (id: number | null) => void
  connect: (hostId: number, credentials: ConnectCredentials) => Promise<ConnectOutcomeInfo>
  disconnect: (hostId: number) => Promise<void>
  /**
   * 重连：有内存缓存凭据时静默自动重连（终端掉线免输密码）；
   * 无缓存凭据则置为异常态，由 UI 引导用户输入密码。
   */
  reconnect: (hostId: number) => Promise<void>
  toggleConnection: (hostId: number, credentials?: ConnectCredentials) => Promise<void>
  setServerStatus: (hostId: number, status: ServerStatus, error?: string) => void
  reorderHosts: (orderedIds: number[]) => void
}

export const useHostStore = create<HostState>((set, get) => {
  function patchServerState(hostId: number, patch: Partial<ServerUIState>) {
    const current = get().serverStates
    set({
      serverStates: {
        ...current,
        [hostId]: { ...current[hostId], ...patch }
      }
    })
  }

  return {
    hosts: [],
    currentHostId: null,
    serverStates: {},
    loaded: false,

    ensureState(hostId: number): ServerUIState {
      const existing = get().serverStates[hostId]
      if (existing) return existing
      const newState: ServerUIState = { status: 'disconnected', sessionId: null }
      set({ serverStates: { ...get().serverStates, [hostId]: newState } })
      return newState
    },

    async loadHosts() {
      const hosts = await hostApi.list()
      set({ hosts, loaded: true })
      hosts.forEach((h) => {
        if (h.id !== undefined) get().ensureState(h.id)
      })
    },

    async reconcile() {
      // 主机列表是对账前提；未加载先加载。
      if (!get().loaded) {
        try {
          await get().loadHosts()
        } catch {
          return
        }
      }
      const hosts = get().hosts
      if (hosts.length === 0) return
      let active: { sessionId: string; hostId: number }[] = []
      try {
        const r = await sshApi.activeSessions()
        active = (r.sessions ?? [])
          .filter((s) => hosts.some((h) => h.id === s.hostId))
          .map((s) => ({ sessionId: s.sessionId, hostId: s.hostId }))
      } catch {
        // 后端不可达：保留现状（页面已有后端不可达提示），不覆盖连接态。
        return
      }

      // 每台主机至多认领一个存活会话。
      const connectedByHost = new Map<number, string>()
      for (const a of active) {
        if (!connectedByHost.has(a.hostId)) connectedByHost.set(a.hostId, a.sessionId)
      }

      const nextStates: Record<number, ServerUIState> = {}
      for (const h of hosts) {
        if (h.id === undefined) continue
        const sid = connectedByHost.get(h.id)
        const prev = get().serverStates[h.id]
        nextStates[h.id] = sid !== undefined
          ? { status: 'connected', sessionId: sid, error: undefined, pendingTrust: undefined }
          // 连接中的瞬时态不被对账打回；其余统一为未连接。
          : prev?.status === 'connecting'
            ? prev
            : { status: 'disconnected', sessionId: null }
      }

      // 恢复当前主机选择：优先上次选择且仍连着的；其次任意已连接主机；
      // 都没有则仍选中上次选择的主机（处于未连接态，UI 可直接引导重连）。
      const lastId = getLastHostId()
      const currentId = get().currentHostId
      const connectedIds = [...connectedByHost.keys()]
      let nextCurrent: number | null = null
      if (currentId !== null && hosts.some((h) => h.id === currentId)) {
        nextCurrent = currentId
      } else if (lastId !== null && hosts.some((h) => h.id === lastId)) {
        nextCurrent = lastId
      } else if (connectedIds.length > 0) {
        nextCurrent = connectedIds[0]
      } else if (hosts[0]?.id !== undefined) {
        nextCurrent = hosts[0].id
      }
      set({ serverStates: nextStates, currentHostId: nextCurrent })
      if (nextCurrent !== null) setLastHostId(nextCurrent)
    },

    async addHost(host: Host) {
      const created = await hostApi.create(host)
      set({ hosts: [...get().hosts, created] })
      if (created.id !== undefined) get().ensureState(created.id)
      return created
    },

    async updateHost(id: number, host: Host) {
      const updated = await hostApi.update(id, host)
      set({ hosts: get().hosts.map((h) => (h.id === id ? updated : h)) })
      return updated
    },

    async removeHost(id: number) {
      const state = get().serverStates[id]
      credentialCache.delete(id)
      if (state?.sessionId) {
        try { await sshApi.disconnect(state.sessionId) } catch { /* ignore */ }
      }
      if (getLastHostId() === id) setLastHostId(null)
      await hostApi.remove(id)
      const newServerStates = { ...get().serverStates }
      delete newServerStates[id]
      set({
        hosts: get().hosts.filter((h) => h.id !== id),
        serverStates: newServerStates
      })
      if (get().currentHostId === id) {
        const remainingConnected = Object.entries(get().serverStates)
          .filter(([, s]) => s.status === 'connected')
          .map(([sid]) => Number(sid))
        set({ currentHostId: remainingConnected.length > 0 ? remainingConnected[0] : null })
      }
    },

    selectHost(id: number | null) {
      if (id === null) {
        set({ currentHostId: null })
        return
      }
      get().ensureState(id)
      set({ currentHostId: id })
      setLastHostId(id)
    },

    async connect(hostId: number, credentials: ConnectCredentials): Promise<ConnectOutcomeInfo> {
      const host = get().hosts.find((h) => h.id === hostId)
      if (!host) return { status: 'error' }
      get().ensureState(hostId)
      patchServerState(hostId, { status: 'connecting', error: undefined, pendingTrust: undefined })
      try {
        const res = await sshApi.connect(hostId, credentials)
        if (res.status === 'need_trust') {
          const pendingTrust: PendingTrust = {
            fingerprint: res.fingerprint || '',
            address: res.address || '',
            port: res.port || 22,
            reason: res.trustReason,
          }
          // 不建立连接，进入 error 态并把待确认指纹挂到 UI 态，由调用方弹确认框
          patchServerState(hostId, {
            status: 'error',
            sessionId: null,
            pendingTrust,
            error: '服务器主机密钥指纹待确认',
          })
          return { status: 'need_trust', pendingTrust }
        }
        // 连接成功：记住当前主机与凭据（凭据仅内存，不落盘），切页/刷新后可免重输或自动对账。
        credentialCache.set(hostId, credentials)
        patchServerState(hostId, {
          sessionId: res.sessionId,
          status: 'connected',
          error: undefined,
          pendingTrust: undefined,
        })
        set({ currentHostId: hostId })
        setLastHostId(hostId)
        return { status: 'connected' }
      } catch (e: any) {
        patchServerState(hostId, { status: 'error', sessionId: null, error: formatConnectError(e) })
        return { status: 'error' }
      }
    },

    async disconnect(hostId: number) {
      const state = get().ensureState(hostId)
      // 用户显式断开：清掉内存凭据，避免之后在用户不知情时自动重连。
      credentialCache.delete(hostId)
      if (state.sessionId) {
        try { await sshApi.disconnect(state.sessionId) } catch { /* ignore */ }
      }
      patchServerState(hostId, { sessionId: null, status: 'disconnected', error: undefined })
    },

    async reconnect(hostId: number) {
      // 先断开残留 session
      const prev = get().serverStates[hostId]
      if (prev?.sessionId) {
        try { await sshApi.disconnect(prev.sessionId) } catch { /* ignore */ }
      }
      patchServerState(hostId, { sessionId: null, pendingTrust: undefined })
      // 本浏览器会话内连过（或私钥认证无需密码）→ 静默自动重连，终端掉线免输密码；
      // 没有可用凭据（典型：刷新页面后后端会话也过期了）→ 置异常态，由 UI 引导输入密码。
      const cached = credentialCache.get(hostId)
      if (cached !== undefined) {
        await get().connect(hostId, cached)
      } else {
        patchServerState(hostId, {
          status: 'error',
          error: '连接已断开，请重新输入密码连接',
        })
      }
    },

    async toggleConnection(hostId: number, credentials: ConnectCredentials = {}) {
      const state = get().ensureState(hostId)
      if (state.status === 'connected') {
        await get().disconnect(hostId)
      } else {
        await get().connect(hostId, credentials)
      }
    },

    setServerStatus(hostId: number, status: ServerStatus, error?: string) {
      get().ensureState(hostId)
      patchServerState(hostId, { status, error })
    },

    reorderHosts(orderedIds: number[]) {
      const currentHosts = get().hosts
      const hostMap = new Map(currentHosts.map((h) => [h.id, h]))
      const reordered = orderedIds.map((id) => hostMap.get(id)).filter(Boolean) as Host[]
      set({ hosts: reordered })
      hostApi.reorder(orderedIds).catch(() => get().loadHosts())
    },
  }
})

// ---- Selectors ----

export function selectCurrentHost(state: HostState): Host | null {
  return state.hosts.find((h) => h.id === state.currentHostId) || null
}

export function selectCurrentState(state: HostState): ServerUIState | null {
  if (state.currentHostId == null) return null
  return state.serverStates[state.currentHostId] || null
}

export function selectSessionId(state: HostState): string | null {
  return selectCurrentState(state)?.sessionId || null
}

export function selectConnectedHostIds(state: HostState): number[] {
  return Object.entries(state.serverStates)
    .filter(([, s]) => s.status === 'connected')
    .map(([id]) => Number(id))
}
