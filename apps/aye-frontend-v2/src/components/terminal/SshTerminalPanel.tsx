import React, { useRef, useState, useEffect, useImperativeHandle, forwardRef, useCallback, memo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { Power, Plus, RotateCw } from 'lucide-react'
import { message } from 'antd'
import {
  useHostStore,
  selectSessionId,
  selectCurrentHost,
  selectCurrentState,
} from '@/store/useHostStore'
import { useTerminalStore } from '@/store/useTerminalStore'
import { getBackendInfo, getWsBase } from '@/api/request'
import ConnectCard from '@/components/servers/ConnectCard'
import { useUploadStore } from '@/store/useUploadStore'
import { readDroppedEntries, collectDirectoryFiles } from '@/lib/dropEntries'
import '@/styles/terminal.css'

export interface SshTerminalPanelRef {
  writeAiCommand: (command: string) => void
  writeAiOutput: (output: string) => void
  writeAiResult: (exitCode: number, durationMs?: number) => void
  sendCommand: (command: string) => void
  getCwd: () => string
  getFontSize: () => number
  increaseFontSize: () => void
  decreaseFontSize: () => void
  writeUploadNote: (note: string) => void
}

interface SshTerminalPanelProps {
  onCwdChange?: (cwd: string) => void
  hideHeader?: boolean
}

/** 终端画布配色（slate 系深色，与 v1 getTerminalTheme 一致） */
const TERMINAL_THEME = {
  background: '#0f172a',
  foreground: '#e2e8f0',
  cursor: '#e2e8f0',
  cursorAccent: '#0f172a',
  selectionBackground: 'rgba(56,189,248,0.35)',
  black: '#0f172a',
  red: '#f87171',
  green: '#4ade80',
  yellow: '#facc15',
  blue: '#60a5fa',
  magenta: '#c084fc',
  cyan: '#22d3ee',
  white: '#e2e8f0',
  brightBlack: '#475569',
  brightRed: '#f87171',
  brightGreen: '#4ade80',
  brightYellow: '#facc15',
  brightBlue: '#60a5fa',
  brightMagenta: '#c084fc',
  brightCyan: '#22d3ee',
  brightWhite: '#f8fafc',
}

// memo：终端面板只接收 hideHeader/ref，AI 流式期间父页面每帧重渲染不应波及 xterm 组件。
const SshTerminalPanel = memo(forwardRef<SshTerminalPanelRef, SshTerminalPanelProps>(
  ({ onCwdChange, hideHeader = false }, ref) => {
    const navigate = useNavigate()

    const terminal = useRef<Terminal | null>(null)
    const fitAddon = useRef<FitAddon | null>(null)
    const ws = useRef<WebSocket | null>(null)
    const cwd = useRef('/')
    const wsReady = useRef(false)
    const shellReady = useRef(false)
    const dataReceivedRef = useRef(false)
    const fitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    const containerRef = useRef<HTMLDivElement | null>(null)

    // 心跳与标签页可见性
    const heartbeatTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
    const lastPongAtRef = useRef<number>(0)
    const heartbeatTimedOutRef = useRef(false)
    const tabHiddenAtRef = useRef<number | null>(null)
    const retryCountRef = useRef(0)
    const backendReconnectingRef = useRef(false)
    const onDataDisposable = useRef<{ dispose: () => void } | null>(null)

    const [connectionError, setConnectionError] = useState<string | null>(null)
    const [localFontSize, setLocalFontSize] = useState<number | null>(null)
    const [dropTarget, setDropTarget] = useState<string | null>(null)

    /**
     * 终端拖放：OS 拖入的文件夹必须经 entries API 递归收集（dataTransfer.files
     * 不提供 webkitRelativePath，直接用会把目录内文件平铺到 cwd）。
     */
    const handleTerminalDrop = async (e: React.DragEvent<HTMLDivElement>): Promise<void> => {
      e.preventDefault()
      setDropTarget(null)
      if (!sessionId) return
      try {
        const entries = await readDroppedEntries(e)
        const target = cwd.current // 跟踪不到时保持初值 '/'（家目录语义）
        for (const d of entries.filter((x) => x.kind === 'dir')) {
          if (d.entry?.isDirectory) {
            const files = await collectDirectoryFiles(
              d.entry as FileSystemDirectoryEntry, d.file.name)
            if (files.length > 0) {
              useUploadStore.getState().enqueue({
                kind: 'directory', sessionId, remoteDir: target, files,
              })
            }
          }
        }
        for (const f of entries.filter((x) => x.kind === 'file').map((x) => x.file)) {
          useUploadStore.getState().enqueue({
            kind: 'file', sessionId, remoteDir: target, file: f,
          })
        }
      } catch (err) {
        // entries/递归读取失败时给出可见反馈，避免拖放静默无效
        message.error(`读取拖入内容失败：${err instanceof Error ? err.message : err}`)
      }
    }

    const storeSessionId = useHostStore(selectSessionId)
    const currentHost = useHostStore(selectCurrentHost)
    const currentState = useHostStore(selectCurrentState)
    const disconnect = useHostStore((s) => s.disconnect)
    const reconnectHost = useHostStore((s) => s.reconnect)
    const setServerStatus = useHostStore((s) => s.setServerStatus)

    const currentStatus = currentState?.status || 'disconnected'
    const sessionId = storeSessionId

    const terminalFontSize = localFontSize ?? 14
    const terminalFontFamily = 'JetBrains Mono, Menlo, Monaco, Consolas, monospace'
    const terminalScrollback = 10000

    const HEARTBEAT_INTERVAL = 20000
    const HEARTBEAT_TIMEOUT = 90000
    const MAX_RETRIES = 5

    const handleConnectAction = async () => {
      if (!currentHost?.id) {
        navigate('/settings/hosts')
        return
      }
      const id = currentHost.id
      if (currentStatus === 'connected') {
        await disconnect(id)
      } else {
        // 未连接/异常：有缓存凭据（含私钥免密）则自动重连，
        // 否则置异常态，由下方空态的连接卡片引导输入密码——全程不离开对话页。
        await reconnectHost(id)
      }
    }

    // ── 待发命令消费（AI 注入命令在 WS/shell 就绪后补发） ──
    const tryConsumePendingCommands = useCallback(() => {
      const { pendingCommands } = useTerminalStore.getState()
      if (pendingCommands.length === 0) return
      if (!ws.current || ws.current.readyState !== WebSocket.OPEN || !wsReady.current || !shellReady.current) return
      const consumePendingCommands = useTerminalStore.getState().consumePendingCommands
      const socket = ws.current
      consumePendingCommands((cmd) => socket.send(cmd + '\r'))
    }, [])

    // ── cd 追踪 ──
    const normalizeCwd = (base: string, target: string): string => {
      let t = target.trim()
      if (t === '-' || t === '') return base
      if (t === '~' || t.startsWith('~/')) t = '/root' + t.slice(1)
      const absolute = t.startsWith('/') ? t : base.replace(/\/+$/, '') + '/' + t
      const parts = absolute.split('/').filter((p) => p && p !== '.')
      const stack: string[] = []
      for (const p of parts) {
        if (p === '..') stack.pop()
        else stack.push(p)
      }
      return '/' + stack.join('/')
    }

    const handleCdInput = (term: Terminal) => {
      const buffer = term.buffer.active
      const line = buffer.getLine(buffer.cursorY)?.translateToString(true) || ''
      const promptMatch = line.match(/[$#>%]([^$#>%]*)$/)
      const input = promptMatch ? promptMatch[1].trim() : line.trim()
      if (input.startsWith('cd ')) {
        const target = input.slice(3).trim()
        const newCwd = normalizeCwd(cwd.current, target)
        if (newCwd !== cwd.current) {
          cwd.current = newCwd
          onCwdChange?.(cwd.current)
        }
      }
    }

    // ── 心跳 ──
    const stopHeartbeat = () => {
      if (heartbeatTimerRef.current) {
        clearInterval(heartbeatTimerRef.current)
        heartbeatTimerRef.current = null
      }
    }

    const startHeartbeat = () => {
      stopHeartbeat()
      lastPongAtRef.current = Date.now()
      heartbeatTimerRef.current = setInterval(() => {
        const socket = ws.current
        if (!socket || socket.readyState !== WebSocket.OPEN) {
          stopHeartbeat()
          return
        }
        if (Date.now() - lastPongAtRef.current > HEARTBEAT_TIMEOUT) {
          heartbeatTimedOutRef.current = true
          stopHeartbeat()
          socket.close()
          return
        }
        try {
          socket.send('__ping__')
        } catch {
          heartbeatTimedOutRef.current = true
          stopHeartbeat()
          socket.close()
        }
      }, HEARTBEAT_INTERVAL)
    }

    // ── WebSocket 连接（含重试/关闭码/心跳/控制消息） ──
    const connectWebSocket = (term: Terminal, wsUrl: string, retryNum: number) => {
      stopHeartbeat()
      if (ws.current) {
        ws.current.onopen = null
        ws.current.onmessage = null
        ws.current.onclose = null
        ws.current.onerror = null
        ws.current.close()
      }

      const socket = new WebSocket(wsUrl)
      ws.current = socket
      shellReady.current = false
      dataReceivedRef.current = false
      heartbeatTimedOutRef.current = false
      backendReconnectingRef.current = false

      const connectTimeout = setTimeout(() => {
        if (socket.readyState === WebSocket.CONNECTING) socket.close()
      }, 5000)

      const handleFinalError = (message: string, shouldReconnectHost = true) => {
        setConnectionError(message)
        safeFit()
        if (currentHost?.id) {
          setServerStatus(currentHost.id, 'error')
          if (shouldReconnectHost) reconnectHost(currentHost.id)
        }
      }

      socket.onopen = () => {
        clearTimeout(connectTimeout)
        retryCountRef.current = 0
        wsReady.current = true
        lastPongAtRef.current = Date.now()
        startHeartbeat()
        if (currentHost?.id) setServerStatus(currentHost.id, 'connected')
      }

      socket.onmessage = (event) => {
        const data = event.data as string
        if (data === '__pong__') {
          lastPongAtRef.current = Date.now()
          return
        }
        // 后端控制消息（当前后端不发，防御性兼容）
        if (data.startsWith('__aye_')) {
          const ctrlEnd = data.indexOf('__', 11)
          if (ctrlEnd !== -1) {
            const ctrlType = data.slice(11, ctrlEnd)
            const payloadStr = data.slice(ctrlEnd + 2)
            let payload: any = null
            if (payloadStr) {
              try { payload = JSON.parse(payloadStr) } catch { /* ignore */ }
            }
            switch (ctrlType) {
              case 'reconnecting':
                backendReconnectingRef.current = true
                shellReady.current = false
                break
              case 'reconnected':
                backendReconnectingRef.current = false
                dataReceivedRef.current = false
                shellReady.current = false
                retryCountRef.current = 0
                break
              case 'session_ended':
                backendReconnectingRef.current = false
                term.writeln('\r\n\x1b[31m[会话已结束]\x1b[0m')
                setConnectionError('会话已结束，请重新连接服务器')
                if (currentHost?.id) setServerStatus(currentHost.id, 'error')
                break
              case 'disconnected':
                backendReconnectingRef.current = false
                term.writeln('\r\n\x1b[31m[连接已断开]\x1b[0m')
                handleFinalError('连接已断开，重连失败，请手动重新连接')
                break
            }
            return
          }
        }
        if (data.startsWith('\r\n[ERROR] ') || data.startsWith('[ERROR] ')) {
          const msg = data.replace(/^\r?\n?\[ERROR\]\s*/, '').trim()
          handleFinalError(msg)
          return
        }
        term.write(data)

        if (!dataReceivedRef.current) {
          dataReceivedRef.current = true
          setTimeout(() => {
            shellReady.current = true
            backendReconnectingRef.current = false
            tryConsumePendingCommands()
          }, 300)
        }
      }

      socket.onclose = (ev) => {
        clearTimeout(connectTimeout)
        stopHeartbeat()
        wsReady.current = false
        shellReady.current = false
        dataReceivedRef.current = false
        backendReconnectingRef.current = false
        if (!ws.current || ws.current !== socket) return

        if (ev.code === 1000) {
          term.writeln('\r\n\x1b[31m[会话已结束]\x1b[0m')
          setConnectionError('会话已结束，请重新连接服务器')
          if (currentHost?.id) setServerStatus(currentHost.id, 'error')
          return
        }
        if (ev.code === 1011) {
          term.writeln('\r\n\x1b[31m[连接异常断开]\x1b[0m')
          handleFinalError('连接异常断开，请检查网络后重新连接')
          return
        }

        if (retryNum < MAX_RETRIES) {
          retryCountRef.current = retryNum + 1
          const delay = 1000 + 500 * (retryNum + 1)
          setTimeout(() => {
            if (containerRef.current && sessionId && terminal.current === term) {
              connectWebSocket(term, wsUrl, retryNum + 1)
            }
          }, delay)
        } else {
          term.writeln('\r\n\x1b[31m连接已关闭\x1b[0m')
          const message = heartbeatTimedOutRef.current
            ? '连接已超时，请检查网络后重新连接'
            : 'WebSocket 连接失败，请确认后端服务已启动'
          handleFinalError(message)
        }
      }

      socket.onerror = () => {
        clearTimeout(connectTimeout)
        stopHeartbeat()
        if (retryNum >= MAX_RETRIES) {
          handleFinalError(`WebSocket 连接失败\n地址：${wsUrl}\n请确认后端服务已启动`)
        }
      }
    }

    // ── 终端初始化 ──
    const initTerminal = () => {
      if (!containerRef.current || !sessionId) return
      if (!containerRef.current.classList.contains('terminal-container')) return

      disposeTerminal()
      setConnectionError(null)
      retryCountRef.current = 0
      shellReady.current = false
      dataReceivedRef.current = false

      const term = new Terminal({
        fontFamily: terminalFontFamily,
        fontSize: terminalFontSize,
        theme: TERMINAL_THEME,
        cursorBlink: true,
        cursorStyle: 'block' as const,
        scrollback: terminalScrollback,
      })
      const fit = new FitAddon()
      term.loadAddon(fit)
      term.loadAddon(new WebLinksAddon())
      term.open(containerRef.current)

      // 剪贴板：Ctrl+C 复制选区，Ctrl+V 粘贴
      term.attachCustomKeyEventHandler((e: KeyboardEvent) => {
        if (e.type !== 'keydown') return true
        if ((e.ctrlKey || e.metaKey) && (e.key === 'c' || e.key === 'C') && term.hasSelection()) {
          const selection = term.getSelection()
          if (selection) {
            navigator.clipboard.writeText(selection).catch(() => {})
            term.clearSelection()
          }
          return false
        }
        if ((e.ctrlKey || e.metaKey) && (e.key === 'v' || e.key === 'V')) {
          navigator.clipboard.readText().then((text) => {
            if (text) {
              const socket = ws.current
              if (socket && socket.readyState === WebSocket.OPEN) socket.send(text)
            }
          }).catch(() => {})
          return false
        }
        return true
      })

      terminal.current = term
      fitAddon.current = fit

      setTimeout(() => {
        safeFit()
        setTimeout(() => safeFit(), 200)
      }, 100)

      onDataDisposable.current?.dispose()
      onDataDisposable.current = term.onData((data) => {
        for (const ch of data) {
          if (ch === '\r') handleCdInput(term)
        }
        const socket = ws.current
        if (socket && socket.readyState === WebSocket.OPEN) socket.send(data)
      })

      getBackendInfo().then(async (info) => {
        const sid = sessionId || ''
        const wsBase = await getWsBase()
        const wsUrl = `${wsBase}/terminal/${sid}?token=${encodeURIComponent(info.token)}`
        setTimeout(() => {
          if (containerRef.current && sessionId && terminal.current === term) {
            connectWebSocket(term, wsUrl, 0)
          }
        }, 300)
      })
    }

    const reconnect = () => {
      setConnectionError(null)
      retryCountRef.current = 0
      disposeTerminal()
      setTimeout(() => initTerminal(), 150)
    }

    const safeFit = useCallback(() => {
      if (fitTimerRef.current) clearTimeout(fitTimerRef.current)
      fitTimerRef.current = setTimeout(() => {
        if (fitAddon.current && containerRef.current && containerRef.current.classList.contains('terminal-container')) {
          fitAddon.current.fit()
        }
      }, 50)
    }, [])

    const disposeTerminal = () => {
      if (fitTimerRef.current) {
        clearTimeout(fitTimerRef.current)
        fitTimerRef.current = null
      }
      stopHeartbeat()
      if (ws.current) {
        ws.current.onopen = null
        ws.current.onmessage = null
        ws.current.onclose = null
        ws.current.onerror = null
        ws.current.close()
        ws.current = null
      }
      wsReady.current = false
      shellReady.current = false
      dataReceivedRef.current = false
      onDataDisposable.current?.dispose()
      onDataDisposable.current = null
      terminal.current?.dispose()
      terminal.current = null
      fitAddon.current = null
    }

    // ── 对外暴露（AI 命令注入） ──
    const writeAiCommand = (command: string) => {
      const term = terminal.current
      if (!term) return
      term.writeln(`\r\n\x1b[33m[AI] $ ${command}\x1b[0m`)
    }
    const writeAiOutput = (output: string) => {
      const term = terminal.current
      if (!term) return
      output.split('\n').forEach((line) => term.writeln(`\x1b[36m[AI]\x1b[0m ${line}`))
    }
    const writeAiResult = (exitCode: number, durationMs?: number) => {
      const term = terminal.current
      if (!term) return
      const color = exitCode === 0 ? '32' : '31'
      const duration = durationMs ? ` (${durationMs}ms)` : ''
      term.writeln(`\x1b[${color}m[AI] exit code: ${exitCode}${duration}\x1b[0m\r\n`)
    }
    const writeUploadNote = (note: string) => {
      terminal.current?.write(`\r\n\x1b[90m${note}\x1b[0m\r\n`)
    }
    const sendCommand = (command: string) => {
      if (!ws.current || ws.current.readyState !== WebSocket.OPEN || !wsReady.current || !shellReady.current) {
        useTerminalStore.setState((s) => ({
          pendingCommands: s.pendingCommands.includes(command) ? s.pendingCommands : [...s.pendingCommands, command]
        }))
        return
      }
      ws.current.send(command + '\r')
    }
    const getCwd = () => cwd.current

    const increaseFontSize = () => {
      const next = Math.min(24, terminalFontSize + 1)
      setLocalFontSize(next)
      if (terminal.current) terminal.current.options.fontSize = next
    }
    const decreaseFontSize = () => {
      const next = Math.max(10, terminalFontSize - 1)
      setLocalFontSize(next)
      if (terminal.current) terminal.current.options.fontSize = next
    }

    // 内部 ref：同时暴露给外部（forwardRef）与 store（AI 注入）
    const internalRef = useRef<SshTerminalPanelRef>(null!)

    useImperativeHandle(ref, () => {
      const refObj: SshTerminalPanelRef = {
        writeAiCommand,
        writeAiOutput,
        writeAiResult,
        sendCommand,
        getCwd,
        getFontSize: () => terminalFontSize,
        increaseFontSize,
        decreaseFontSize,
        writeUploadNote,
      }
      internalRef.current = refObj
      return refObj
    })

    // 注册 terminalRef 到 store
    const setTerminalRef = useTerminalStore((s) => s.setTerminalRef)
    useEffect(() => {
      const timer = setTimeout(() => {
        if (internalRef.current) setTerminalRef(internalRef.current)
      }, 0)
      return () => {
        clearTimeout(timer)
        setTerminalRef(null)
      }
    }, [setTerminalRef])

    // 消费 pending 命令轮询（10s 窗口）
    useEffect(() => {
      const checkInterval = setInterval(() => tryConsumePendingCommands(), 200)
      const stopTimer = setTimeout(() => clearInterval(checkInterval), 10000)
      return () => {
        clearInterval(checkInterval)
        clearTimeout(stopTimer)
      }
    }, [sessionId, tryConsumePendingCommands])

    // session 变化 → 初始化/销毁终端
    useEffect(() => {
      if (!sessionId) {
        disposeTerminal()
        return
      }
      const timer = setTimeout(() => {
        if (!terminal.current) initTerminal()
      }, 50)
      const handleResize = () => safeFit()
      window.addEventListener('resize', handleResize)

      const handleVisibilityChange = () => {
        if (document.hidden) {
          tabHiddenAtRef.current = Date.now()
          stopHeartbeat()
        } else {
          tabHiddenAtRef.current = null
          const socket = ws.current
          if (socket && socket.readyState === WebSocket.OPEN) {
            lastPongAtRef.current = Date.now()
            startHeartbeat()
          } else if (!connectionError && terminal.current) {
            getBackendInfo().then(async (info) => {
              const wsBase = await getWsBase()
              const wsUrl = `${wsBase}/terminal/${sessionId}?token=${encodeURIComponent(info.token)}`
              if (containerRef.current && terminal.current) {
                connectWebSocket(terminal.current, wsUrl, 0)
              }
            })
          }
        }
      }
      document.addEventListener('visibilitychange', handleVisibilityChange)

      return () => {
        clearTimeout(timer)
        window.removeEventListener('resize', handleResize)
        document.removeEventListener('visibilitychange', handleVisibilityChange)
        disposeTerminal()
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sessionId])

    const setContainerRef = useCallback((node: HTMLDivElement | null) => {
      containerRef.current = node
    }, [])

    const connLabel =
      currentStatus === 'connected' ? '断开' : currentStatus === 'error' ? '重新连接' : '连接'

    return (
      <div className="ssh-terminal-panel">
        {!hideHeader && (
          <div className="panel-head">
            <div className="panel-title">
              <span className="prompt">$</span>
              <span>交互式终端</span>
              <span className={`dsh-pill ${currentStatus === 'connected' ? 'active' : ''}`}>
                {currentHost ? `${currentHost.name} (${currentHost.username}@${currentHost.address})` : '未连接'}
              </span>
            </div>
            <div className="panel-tools">
              <div className="font-size-control">
                <button className="dsh-icon-btn" title="减小字号" onClick={decreaseFontSize} disabled={terminalFontSize <= 10}>
                  −
                </button>
                <span className="label">{terminalFontSize}px</span>
                <button className="dsh-icon-btn" title="增大字号" onClick={increaseFontSize} disabled={terminalFontSize >= 24}>
                  +
                </button>
              </div>
              {currentHost && (
                <button
                  className={`dsh-btn dsh-btn-ghost conn-toggle-btn ${currentStatus === 'connected' ? 'connected' : ''}`}
                  disabled={currentStatus === 'connecting'}
                  onClick={handleConnectAction}
                >
                  {currentStatus === 'connected' ? <Power /> : <Plus />}
                  <span>{connLabel}</span>
                </button>
              )}
            </div>
          </div>
        )}

        {!sessionId ? (
          <div key="no-session" className="terminal-notice">
            <ConnectCard />
          </div>
        ) : connectionError ? (
          <div key="error" className="terminal-notice">
            <div className="empty-state">
              <div className="empty-title">终端连接失败</div>
              <div className="empty-desc">{connectionError}</div>
              <button
                className="dsh-btn dsh-btn-outline reconnect-btn"
                onClick={async () => {
                  // SSH 会话还活着（仅终端 WS 抖动）→ 重开 shell；
                  // SSH 已失效 → 用缓存凭据重建连接（无缓存则下方切换为密码连接卡片）。
                  if (currentHost?.id && currentStatus !== 'connected') {
                    await reconnectHost(currentHost.id)
                  }
                  reconnect()
                }}
              >
                <RotateCw />
                重新连接
              </button>
            </div>
          </div>
        ) : (
          <div
            key="terminal"
            className="terminal-wrap"
            onDragEnter={(e) => {
              if (!e.dataTransfer.types?.includes('Files')) return
              e.preventDefault()
              setDropTarget(cwd.current)
            }}
            onDragOver={(e) => {
              if (!e.dataTransfer.types?.includes('Files')) return
              e.preventDefault()
              e.dataTransfer.dropEffect = 'copy'
            }}
            onDragLeave={(e) => {
              if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDropTarget(null)
            }}
            onDrop={handleTerminalDrop}
          >
            <div ref={setContainerRef} className="terminal-container"></div>
            {dropTarget !== null && (
              <div className="tm-drop-float">
                <div className="tm-drop-float-title">松开即上传到服务器</div>
                <div className="tm-drop-float-path">{dropTarget}</div>
              </div>
            )}
          </div>
        )}
      </div>
    )
  }
))

SshTerminalPanel.displayName = 'SshTerminalPanel'

export default SshTerminalPanel
