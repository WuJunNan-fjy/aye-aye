import { create } from 'zustand'
import sessionsApi, { type SessionInfo } from '@/api/sessionsApi'

/**
 * 历史会话全局 store。
 * 会话树挂在应用侧栏（AppSidebar）中，不再从属于对话页，
 * 因此会话列表从 ConversationPage 上提至此，对话页与侧栏共用一份数据。
 */
interface SessionsState {
  sessions: SessionInfo[]
  loading: boolean
  loaded: boolean
  /** 加载列表；force=true 时跳过 loaded 缓存与并发短路。 */
  load: (force?: boolean) => Promise<void>
  /** 删除会话并同步本地列表（后端调用由调用方决定是否还需要其他副作用）。 */
  remove: (id: string) => Promise<void>
}

export const useSessionsStore = create<SessionsState>((set, get) => ({
  sessions: [],
  loading: false,
  loaded: false,

  load: async (force) => {
    if (get().loading) return
    if (get().loaded && !force) return
    set({ loading: true })
    try {
      const r = await sessionsApi.list()
      set({ sessions: r.sessions ?? [], loaded: true })
    } catch (e) {
      console.error('Failed to load sessions:', e)
    } finally {
      set({ loading: false })
    }
  },

  remove: async (id) => {
    await sessionsApi.delete(id)
    set((s) => ({ sessions: s.sessions.filter((x) => x.id !== id) }))
  },
}))
