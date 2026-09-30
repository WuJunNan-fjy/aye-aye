import { create } from 'zustand'
import type { SshTerminalPanelRef } from '@/components/terminal/SshTerminalPanel'

/**
 * 终端 store：AI 侧往终端注入命令的桥。
 * - terminalRef 由 SshTerminalPanel 挂载/卸载时注册
 * - pendingCommands 在终端未就绪时先排队，WS 就绪后一次性消费
 */
interface TerminalState {
  terminalRef: SshTerminalPanelRef | null
  cwd: string
  pendingCommands: string[]

  setTerminalRef: (ref: SshTerminalPanelRef | null) => void
  setCwd: (cwd: string) => void
  sendCommand: (command: string) => void
  /** 由终端组件在 WebSocket 就绪后调用，一次性消费所有待发命令 */
  consumePendingCommands: (send: (cmd: string) => void) => void
}

export const useTerminalStore = create<TerminalState>((set, get) => ({
  terminalRef: null,
  cwd: '/',
  pendingCommands: [],

  setTerminalRef: (ref) => set({ terminalRef: ref }),

  setCwd: (cwd) => set({ cwd }),

  sendCommand: (command: string) => {
    const { terminalRef } = get()
    if (terminalRef) {
      terminalRef.sendCommand(command)
    } else {
      set((s) => ({ pendingCommands: [...s.pendingCommands, command] }))
    }
  },

  consumePendingCommands: (send) => {
    const { pendingCommands } = get()
    if (pendingCommands.length === 0) return
    const cmds = [...pendingCommands]
    set({ pendingCommands: [] })
    for (const cmd of cmds) {
      send(cmd)
    }
  },
}))
