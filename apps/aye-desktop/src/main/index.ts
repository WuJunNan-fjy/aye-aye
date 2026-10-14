import { app, BrowserWindow, ipcMain, safeStorage, shell, Menu, dialog, Tray, nativeImage } from 'electron'
import { spawn, ChildProcess } from 'child_process'
import { join, resolve, sep } from 'path'
import { randomBytes, createHmac } from 'crypto'
import {
  readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync,
  createReadStream, createWriteStream, statSync, rmSync,
} from 'fs'
import { rm } from 'fs/promises'
import http from 'http'
import os from 'os'
import archiver from 'archiver'
import { initAutoUpdater, isUpdateInstallPending } from './updater'

const CREDENTIALS_FILE = 'credentials.json'

let mainWindow: BrowserWindow | null = null
let splashWindow: BrowserWindow | null = null
let tray: Tray | null = null
/** 仅在“真正退出”（托盘退出 / cmd+Q / 系统关机）时置位；普通关窗只隐藏到托盘 */
let isQuitting = false
/** 首次收到托盘区的气球提示，避免每次关窗都弹 */
let trayHintShown = false

// ── 后端状态机 starting / ready / degraded / crashed / stopped / error ──
type BackendPhase = 'starting' | 'ready' | 'degraded' | 'crashed' | 'stopped' | 'error'
let backendProcess: ChildProcess | null = null
let backendPort = 0
let backendPid = 0
let apiToken = ''
/** true = 复用开发机已在 8080 运行的后端，关停时绝不能动它 */
let externalBackend = false
let backendPhase: BackendPhase = 'stopped'
let backendDetail: string | null = null
let backendVersion = ''
let restartCount = 0
let startInProgress: Promise<void> | null = null
const MAX_AUTO_RESTARTS = 1
const START_TIMEOUT_MS = 45000
let startTimeout: NodeJS.Timeout | null = null
let healthTimer: NodeJS.Timeout | null = null
let healthFailures = 0
/** 优雅关停等待进程自然退出的 resolve 句柄 */
let onProcessExit: (() => void) | null = null

/**
 * 查找 Java 后端 fat jar（aye-server-*.jar，Spring Boot repackage 产物）
 */
function resolveServerJar(): string {
  // 打包后：electron-builder extraResources 复制到 resources/backend/server.jar
  const prodJar = join(process.resourcesPath, 'backend', 'server.jar')
  if (existsSync(prodJar)) return prodJar

  // 开发模式：Maven 产物 apps/aye-server/target/aye-server-*.jar（排除 repackage 的 .original）
  const candidateDirs = [
    join(__dirname, '..', '..', '..', 'apps', 'aye-server', 'target'),
    join(process.cwd(), 'apps', 'aye-server', 'target'),
  ]
  for (const dir of candidateDirs) {
    if (!existsSync(dir)) continue
    try {
      const jar = readdirSync(dir).find(
        (f) => /^aye-server-.+\.jar$/.test(f) && !f.endsWith('.original')
      )
      if (jar) return join(dir, jar)
    } catch {
      /* ignore readdir errors */
    }
  }
  console.error('[resolveServerJar] 未找到 aye-server jar，回退到打包路径（可能不存在）')
  return prodJar
}

/**
 * 查找 java 可执行文件：
 * 1. 打包后唯一约定：resources/runtime/bin/java（prepare-backend 用 jlink 生成，打进 extraResources）
 * 2. 环境变量 AYE_JAVA（开发时可指向 D:\jdk17\jdk-17\bin\java.exe）
 * 3. JAVA_HOME\bin\java.exe
 * 4. PATH 上的 java
 */
function resolveJavaExe(): string {
  const exeName = process.platform === 'win32' ? 'java.exe' : 'java'
  const bundled = join(process.resourcesPath, 'runtime', 'bin', exeName)
  if (existsSync(bundled)) return bundled

  const envJava = process.env.AYE_JAVA
  if (envJava && existsSync(envJava)) return envJava

  const javaHome = process.env.JAVA_HOME
  if (javaHome) {
    const cand = join(javaHome, 'bin', 'java.exe')
    if (existsSync(cand)) return cand
  }
  return 'java'
}

/**
 * 检测是否已有后端在运行（开发模式下用户可能手动启动了后端）
 */
function checkExistingBackend(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(`http://127.0.0.1:${port}/api/hosts`, { timeout: 2000 }, (res) => {
      resolve(res.statusCode !== undefined && res.statusCode >= 200 && res.statusCode < 400)
    })
    req.on('error', () => resolve(false))
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
  })
}

function pidFilePath(): string {
  return join(app.getPath('userData'), 'backend.pid')
}

function writePidFile(pid: number): void {
  try {
    writeFileSync(pidFilePath(), String(pid), 'utf-8')
  } catch (e) {
    console.warn('[backend] 写 PID 文件失败', e)
  }
}

function removePidFile(): void {
  try {
    rmSync(pidFilePath(), { force: true })
  } catch { /* ignore */ }
}

/** 状态变更唯一入口：推送给主窗口，托盘 tooltip 同步 */
function setPhase(phase: BackendPhase, detail?: string): void {
  backendPhase = phase
  backendDetail = detail ?? null
  const win = mainWindow
  if (win && !win.isDestroyed()) {
    win.webContents.send('aye:backend-state', { phase, detail: detail ?? null })
  }
  if (tradyAlive()) {
    tray?.setToolTip(
      phase === 'ready'
        ? `Aye（后台运行中 · v${backendVersion || 'dev'}）`
        : phase === 'degraded'
          ? 'Aye（后端响应异常，正在重试）'
          : 'Aye（后台异常）',
    )
  }
}

function tradyAlive(): boolean {
  return !!tray && !tray.isDestroyed()
}

/**
 * 启动（或重启）一次后端；并发调用去重。
 * 复用开发机 8080 时不 spawn、不管停。
 */
function startBackend(): Promise<void> {
  if (startInProgress) return startInProgress
  startInProgress = (async () => {
    setPhase('starting')

    // 开发模式：检测是否已有后端在 8080 端口运行
    const existingPort = 8080
    if (!app.isPackaged && await checkExistingBackend(existingPort)) {
      console.log(`[startBackend] 复用本机后端 :${existingPort}，不 spawn`)
      externalBackend = true
      backendPort = existingPort
      backendPid = 0
      apiToken = ''
      setPhase('ready', 'external')
      return
    }

    externalBackend = false
    backendPort = 0
    backendPid = 0
    // 令牌首次生成后跨崩溃重启复用：渲染端会缓存令牌，重启换新令牌会使其永久 401。
    // 端口仍每次随机，重启后由渲染端订阅 backend-state 重新拉取。
    if (!apiToken) apiToken = randomBytes(32).toString('hex')

    const userData = app.getPath('userData')
    const dataDir = join(userData, 'data')
    const tmpDir = join(userData, 'tmp')
    const logsDir = join(userData, 'logs')
    mkdirSync(dataDir, { recursive: true })
    mkdirSync(tmpDir, { recursive: true })
    mkdirSync(logsDir, { recursive: true })

    const serverJar = resolveServerJar()
    const javaExe = resolveJavaExe()
    console.log('[startBackend] javaExe=', javaExe)
    console.log('[startBackend] serverJar=', serverJar)

    const env = { ...process.env }
    env.PORT = '0'
    env.SERVER_ADDRESS = '127.0.0.1' // 桌面侧仅监听回环，不向局域网暴露无鉴权 REST
    env.AYE_API_TOKEN = apiToken
    env.AYE_DATA_DIR = dataDir

    // JVM 参数（Windows 路径统一用正斜杠，JVM 可识别）：
    // - 编码：握手行/中文日志 UTF-8（stdout.encoding JDK18+ 生效，17 上安全忽略）
    // - Xmx/Metaspace：桌面常驻，限制上限避免小内存机器预留过大
    // - org.sqlite.tmpdir：Program Files 下安装目录不可写，原生库解压到 userData
    // - ErrorFile：JVM 崩溃留 hs_err 日志便于诊断
    const jvmArgs = [
      '-Dfile.encoding=UTF-8',
      '-Dstdout.encoding=UTF-8',
      '-Xmx384m',
      '-XX:MaxMetaspaceSize=256m',
      `-Dorg.sqlite.tmpdir=${tmpDir.split(sep).join('/')}`,
      `-XX:ErrorFile=${logsDir.split(sep).join('/')}/hs_err_pid%p.log`,
      '-jar', serverJar,
    ]

    await new Promise<void>((resolve, reject) => {
      const child = spawn(javaExe, jvmArgs, { env, stdio: ['ignore', 'pipe', 'pipe'] })
      backendProcess = child

      let settled = false
      const fail = (message: string) => {
        if (settled) return
        settled = true
        if (startTimeout) clearTimeout(startTimeout)
        setPhase('error', message)
        reject(new Error(message))
      }
      const ready = (port: number, pid: number, version: string) => {
        if (settled || !Number.isInteger(port) || port <= 0) return
        settled = true
        if (startTimeout) clearTimeout(startTimeout)
        backendPort = port
        backendPid = pid || child.pid || 0
        backendVersion = version || ''
        writePidFile(backendPid)
        setPhase('ready')
        restartCount = 0
        startHealthChecks()
        resolve()
      }

      // 结构化握手契约（AyeApplication 输出）：
      //   AYE_READY {"port":54321,"pid":12348,"version":"0.0.1"}
      const handleReadyLine = (line: string) => {
        const trimmed = line.trim()
        if (trimmed.startsWith('AYE_READY ')) {
          try {
            const payload = JSON.parse(trimmed.slice('AYE_READY '.length))
            ready(Number(payload.port), Number(payload.pid) || 0, String(payload.version ?? ''))
          } catch {
            // 截断/非 JSON 行忽略，等待下一行
          }
          return
        }
        // 兼容旧 jar 的纯文本行
        const legacy = trimmed.match(/running on https?:\/\/[^:\s]+:(\d+)/)
        if (legacy) ready(Number(legacy[1]), child.pid || 0, '')
      }

      // 子进程 chunk 不保证按行边界到达，按 \n 缓冲切分后再解析
      const pipeLines = (stream: NodeJS.ReadableStream | null, log: (line: string) => void) => {
        if (!stream) return
        let buffer = ''
        stream.setEncoding('utf8')
        stream.on('data', (chunk: string) => {
          buffer += chunk
          let nl: number
          while ((nl = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, nl).replace(/\r$/, '')
            buffer = buffer.slice(nl + 1)
            if (line) {
              log(line)
              handleReadyLine(line)
            }
          }
        })
      }
      pipeLines(child.stdout, (l) => console.log('[backend]', l))
      pipeLines(child.stderr, (l) => console.error('[backend]', l))

      child.on('error', (err) => fail(`无法启动 Java 进程：${err.message}`))
      child.on('exit', (code, signal) => {
        stopHealthChecks()
        removePidFile()
        onProcessExit?.()
        onProcessExit = null
        if (!settled) {
          fail(`后端在就绪前退出（code=${code ?? 'null'}${signal ? ` signal=${signal}` : ''}）`)
          return
        }
        if (isQuitting) {
          // 预期内关停
          setPhase('stopped')
          return
        }
        // 运行中崩溃：被杀软杀 / OOM / 异常退出
        setPhase('crashed', `exit code ${code ?? signal ?? 'unknown'}`)
        console.error(`[backend] 运行中崩溃 code=${code} signal=${signal}`)
        if (restartCount < MAX_AUTO_RESTARTS) {
          restartCount += 1
          console.log(`[backend] 自动重启（第 ${restartCount} 次，上限 ${MAX_AUTO_RESTARTS}）`)
          setTimeout(() => { void startBackend().catch(() => { /* phase 已 error */ }) }, 1500)
        }
      })

      startTimeout = setTimeout(() => fail(`后端启动超时（${START_TIMEOUT_MS / 1000}s）`), START_TIMEOUT_MS)
    })
  })().finally(() => {
    startInProgress = null
  })
  return startInProgress
}

/** /api/health 轮询：进程活着但服务假死时给出 degraded 状态（不擅自杀进程） */
function startHealthChecks(): void {
  stopHealthChecks()
  healthFailures = 0
  healthTimer = setInterval(() => {
    if (externalBackend || !backendPort) return
    const req = http.get(
      `http://127.0.0.1:${backendPort}/api/health`,
      { timeout: 4000 },
      (res) => {
        res.resume()
        if (res.statusCode && res.statusCode >= 200 && res.statusCode < 500) {
          healthFailures = 0
          if (backendPhase === 'degraded') setPhase('ready')
        } else {
          healthFailure()
        }
      },
    )
    req.on('error', healthFailure)
    req.on('timeout', () => { req.destroy(); healthFailure() })
  }, 15000)
}

function healthFailure(): void {
  healthFailures += 1
  if (healthFailures >= 4 && backendPhase === 'ready') {
    setPhase('degraded', 'health check failed')
  }
}

function stopHealthChecks(): void {
  if (healthTimer) {
    clearInterval(healthTimer)
    healthTimer = null
  }
}

function waitForProcessExit(timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    if (!backendProcess || backendProcess.killed || backendProcess.exitCode !== null) {
      resolve(true)
      return
    }
    let done = false
    const finish = (exited: boolean) => {
      if (done) return
      done = true
      if (timer) clearTimeout(timer)
      onProcessExit = null
      resolve(exited)
    }
    const timer = setTimeout(() => finish(false), timeoutMs)
    onProcessExit = () => finish(true)
  })
}

/**
 * 分级优雅关停：
 *   1. POST /api/internal/shutdown（Spring graceful：SSE/上传收尾、WAL checkpoint）
 *   2. 等进程自然退出（10s）
 *   3. Windows：taskkill /PID /T /F 杀进程树；其他平台 SIGKILL
 *   4. 再等 3s，仍不死则 Node 层 kill('SIGKILL') 兜底
 * 复用的开发机 8080 后端不动。
 */
async function gracefulStopBackend(): Promise<void> {
  stopHealthChecks()
  if (externalBackend) {
    console.log('[stopBackend] 复用的外部后端，跳过关停')
    return
  }
  const proc = backendProcess
  if (!proc || proc.killed || proc.exitCode !== null) {
    removePidFile()
    return
  }

  // 1. 内部 shutdown 端点（令牌鉴权）
  if (backendPort && apiToken) {
    try {
      await new Promise<void>((resolve) => {
        const req = http.request(
          `http://127.0.0.1:${backendPort}/api/internal/shutdown`,
          {
            method: 'POST',
            timeout: 3000,
            headers: { 'X-API-Token': apiToken },
          },
          (res) => { res.resume(); res.on('end', resolve); res.on('error', () => resolve()) },
        )
        req.on('error', () => resolve()) // 连接被关停过程中断掉属正常
        req.on('timeout', () => { req.destroy(); resolve() })
        req.end()
      })
    } catch { /* ignore */ }
  }

  // 2. 等自然退出
  const exited = await waitForProcessExit(10000)
  if (exited) {
    console.log('[stopBackend] 后端已优雅退出')
    removePidFile()
    return
  }

  // 3. 强杀进程树（Windows kill('SIGTERM') 即 TerminateProcess，但不杀子进程树；taskkill /T 更稳）
  const pid = backendPid || proc.pid
  console.warn(`[stopBackend] 优雅关停超时，强杀 PID=${pid}`)
  if (process.platform === 'win32' && pid) {
    await new Promise<void>((resolve) => {
      const t = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
      t.on('error', () => resolve())
      t.on('exit', () => resolve())
    })
  } else {
    try { proc.kill('SIGKILL') } catch { /* ignore */ }
  }

  // 4. 兜底再等
  const exited2 = await waitForProcessExit(3000)
  if (!exited2 && pid) {
    try { process.kill(pid, 'SIGKILL') } catch { /* already gone */ }
  }
  removePidFile()
}

function detectDevServerUrl(): Promise<string | null> {
  return new Promise((resolve) => {
    const ports = [5173, 5174, 5175, 5176, 5177, 5178]
    let resolved = false
    let checked = 0
    const done = (url: string | null) => {
      if (resolved) return
      resolved = true
      if (url) console.log('[detectDevServerUrl] found', url)
      resolve(url)
    }
    ports.forEach((port) => {
      const url = `http://localhost:${port}/`
      const req = http.get(url, { timeout: 1500 }, (res) => {
        if (!(res.statusCode && res.statusCode >= 200 && res.statusCode < 400)) {
          checked++
          if (checked === ports.length) done(null)
          return
        }
        // 读取响应体并校验是否为 Aye 页面，
        // 避免命中本机其他项目（如 OpenCode）的 dev server 导致加载空白页
        let body = ''
        let settled = false
        const finish = (match: boolean) => {
          if (settled) return
          settled = true
          res.removeAllListeners()
          res.destroy()
          if (match) {
            done(url)
          } else {
            console.log(`[detectDevServerUrl] skip ${url} (not a Aye page)`)
            checked++
            if (checked === ports.length) done(null)
          }
        }
        res.setTimeout(2000, () => finish(false))
        res.setEncoding('utf8')
        res.on('data', (chunk: string) => {
          body += chunk
          if (body.length >= 4096) finish(body.includes('Aye'))
        })
        res.on('end', () => finish(body.includes('Aye')))
      })
      req.on('error', () => {
        checked++
        if (checked === ports.length) done(null)
      })
      req.on('timeout', () => {
        req.destroy()
        checked++
        if (checked === ports.length) done(null)
      })
    })
  })
}

function getCredentialsPath(): string {
  const dir = app.getPath('userData')
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
  return join(dir, CREDENTIALS_FILE)
}

function readCredentials(): Record<string, string> {
  try {
    const data = readFileSync(getCredentialsPath(), 'utf-8')
    return JSON.parse(data)
  } catch {
    return {}
  }
}

function writeCredentials(creds: Record<string, string>): void {
  writeFileSync(getCredentialsPath(), JSON.stringify(creds, null, 2))
}

function resolveIconPath(): string {
  const iconNames = {
    win32: 'icon.ico',
    darwin: 'icon.icns',
    linux: 'icon.png'
  }
  const iconName = iconNames[process.platform as keyof typeof iconNames] || 'icon.png'
  
  const possiblePaths = [
    join(__dirname, '..', '..', 'build', iconName),
    join(__dirname, '..', 'build', iconName),
    join(process.resourcesPath, 'build', iconName),
    join(app.getAppPath(), 'build', iconName),
    join(__dirname, '..', '..', 'build', 'icon.png'),
  ]
  
  for (const p of possiblePaths) {
    if (existsSync(p)) return p
  }
  return join(__dirname, '..', '..', 'build', iconName)
}

/** 托盘/通知用 PNG 图标（extraResources 会把 build/icon.png 带到 resources/build/） */
function resolveTrayIconPath(): string {
  const candidates = [
    join(__dirname, '..', '..', 'build', 'icon.png'),
    join(__dirname, '..', 'build', 'icon.png'),
    join(process.resourcesPath, 'build', 'icon.png'),
    join(app.getAppPath(), 'build', 'icon.png'),
  ]
  return candidates.find((p) => existsSync(p)) ?? candidates[0]
}

function showMainWindow(): void {
  const win = mainWindow
  if (!win) {
    // 窗口已被销毁（macOS 关窗后点 Dock/托盘）：重新创建
    void createWindow()
    return
  }
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

/** 真退出请求：实际关停在 before-quit 中异步完成（先优雅停后端再 app.exit） */
function quitApp(): void {
  isQuitting = true
  app.quit()
}

// ── 启动闪屏：秒开窗口，避免 JVM 冷启动 3~8s 内屏幕无反应 ──

const SPLASH_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; overflow: hidden;
    background: transparent; font-family: "Segoe UI", "Microsoft YaHei", sans-serif;
    -webkit-user-select: none; user-select: none; }
  .card { width: 100vw; height: 100vh; background: #171717;
    border-radius: 16px; display: flex; flex-direction: column;
    align-items: center; justify-content: center; gap: 22px; }
  .logo { width: 72px; height: 72px; border-radius: 18px;
    box-shadow: 0 8px 28px rgba(0,0,0,.45); }
  .name { color: #fff; font-size: 17px; font-weight: 600; letter-spacing: 3px; }
  .status { color: #9a9a9a; font-size: 12px; min-height: 16px; text-align: center;
    padding: 0 28px; line-height: 1.5; word-break: break-word; }
  .track { width: 168px; height: 3px; border-radius: 2px; background: rgba(255,255,255,.10);
    overflow: hidden; position: relative; }
  .beam { position: absolute; top: 0; left: 0; width: 56px; height: 100%;
    border-radius: 2px;
    background: linear-gradient(90deg, rgba(255,255,255,0) 0%, #ffffff 50%, rgba(255,255,255,0) 100%);
    animation: flow 1.25s linear infinite; }
  @keyframes flow { 0% { transform: translateX(-64px);} 100% { transform: translateX(176px);} }
  .error-box { display: none; flex-direction: column; align-items: center; gap: 14px;
    padding: 0 30px; }
  .error-msg { color: #e08282; font-size: 12px; line-height: 1.6; text-align: center;
    max-height: 96px; overflow: auto; }
  .quit-btn { appearance: none; border: 1px solid rgba(255,255,255,.28);
    background: transparent; color: #ddd; font-size: 12px; padding: 7px 22px;
    border-radius: 8px; cursor: pointer; }
  .quit-btn:hover { background: rgba(255,255,255,.08); }
</style>
</head>
<body>
  <div class="card">
    <svg class="logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
      <rect width="448" height="448" x="32" y="32" rx="96" fill="#0F1115"/>
      <circle cx="254" cy="262" r="76" fill="none" stroke="#F9FAFB" stroke-width="32"/>
      <path d="M330 172 V340" fill="none" stroke="#F9FAFB" stroke-width="32" stroke-linecap="round"/>
      <circle cx="254" cy="262" r="25" fill="#34D399">
        <animate attributeName="opacity" values="1;0.5;1" keyTimes="0;0.5;1" calcMode="spline" keySplines="0.4 0 0.6 1;0.4 0 0.6 1" dur="2.4s" repeatCount="indefinite"/>
      </circle>
    </svg>
    <div class="name">Aye</div>
    <div id="loading">
      <div class="track"><div class="beam"></div></div>
    </div>
    <div class="status" id="status">正在启动本地后端…</div>
    <div class="error-box" id="errorBox">
      <div class="error-msg" id="errorMsg"></div>
      <button class="quit-btn" id="quitBtn" type="button">退出 Aye</button>
    </div>
  </div>
  <script>
    function showError(msg) {
      document.getElementById('status').textContent = '启动失败';
      document.getElementById('loading').style.display = 'none';
      document.getElementById('errorBox').style.display = 'flex';
      document.getElementById('errorMsg').textContent = msg || '未知错误';
    }
    document.getElementById('quitBtn').addEventListener('click', function () { window.close(); });
  </script>
</body>
</html>`

function createSplash(): void {
  splashWindow = new BrowserWindow({
    width: 360,
    height: 440,
    frame: false,
    transparent: true,
    resizable: false,
    movable: true,
    center: true,
    alwaysOnTop: true,
    skipTaskbar: false,
    show: true,
    backgroundColor: '#00000000',
    icon: resolveIconPath(),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  })
  splashWindow.setMenuBarVisibility(false)
  void splashWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(SPLASH_HTML))
  // 启动阶段闪屏被关闭（Alt+F4）= 没有主窗口可用，先停后端再退出
  splashWindow.on('closed', () => {
    splashWindow = null
    if (backendPhase !== 'ready' && !isQuitting) {
      isQuitting = true
      void gracefulStopBackend().finally(() => app.exit(1))
    }
  })
}

function destroySplash(): void {
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.close()
  }
  splashWindow = null
}

function showSplashError(message: string): void {
  console.error('[splash] 启动失败：', message)
  if (splashWindow && !splashWindow.isDestroyed()) {
    void splashWindow.webContents.executeJavaScript(
      `showError(${JSON.stringify(message)})`,
    ).catch(() => { /* 页面未就绪 */ })
  }
}

function createTray(): void {
  try {
    // 托盘建议 16x16，512 原图直接用在 Windows 上会糊，这里显式缩放
    const image = nativeImage.createFromPath(resolveTrayIconPath())
    const icon = image.isEmpty()
      ? image
      : image.resize({ width: 16, height: 16 })
    tray = new Tray(icon)
    tray.setToolTip('Aye（后台运行中）')
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: '显示主界面', click: showMainWindow },
        { type: 'separator' },
        { label: '退出 Aye', click: quitApp },
      ]),
    )
    tray.on('click', showMainWindow)
    tray.on('double-click', showMainWindow)
  } catch (e) {
    console.error('[tray] 创建托盘失败：', e)
  }
}

async function createWindow(): Promise<void> {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    frame: false,
    show: false, // 由闪屏过渡，渲染就绪后再显示，避免白屏
    resizable: true,
    maximizable: true,
    minimizable: true,
    autoHideMenuBar: true,
    icon: resolveIconPath(),
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  // 移除系统默认菜单栏，避免 Windows 上出现 File/Edit/View 等原生菜单
  mainWindow.setMenu(null)
  Menu.setApplicationMenu(null)

  // 优先使用 electron-vite 注入的 renderer dev server URL，
  // 避免扫描端口时命中本机其他项目（如 OpenCode）的 dev server，导致加载错误页面/白屏
  const devServerUrl = process.env.VITE_DEV_SERVER_URL || (await detectDevServerUrl())
  if (devServerUrl) {
    mainWindow.loadURL(devServerUrl)
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }

  // 渲染就绪：主窗口淡入、关掉闪屏（闪屏关闭不会再触发退出，因 phase=ready）
  mainWindow.once('ready-to-show', () => {
    destroySplash()
    mainWindow?.show()
    mainWindow?.focus()
  })

  // 渲染资源加载失败（如 dev server 已关）：在闪屏上给出错误，避免无限 loading
  mainWindow.webContents.on('did-fail-load', (_e, errorCode, errorDescription, validatedURL) => {
    if (errorCode === -3) return // ERR_ABORTED：路由内跳转/刷新打断，忽略
    if (mainWindow?.isVisible()) return
    showSplashError(`界面加载失败（${errorCode} ${errorDescription}）：${validatedURL}`)
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  // 关闭按钮（含自定义标题栏的 aye:window-close）默认只隐藏到托盘，
  // 后端继续运行；仅在 isQuitting（托盘退出/cmd+Q/系统关机）时放行真正关闭
  mainWindow.on('close', (e) => {
    if (!isQuitting) {
      e.preventDefault()
      mainWindow?.hide()
      if (tray && !trayHintShown) {
        trayHintShown = true
        tray.displayBalloon({
          iconType: 'info',
          title: 'Aye 仍在后台运行',
          content: '关闭窗口不会退出程序，本地后端与连接继续保持。右键托盘图标可退出。',
        })
      }
    }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

/** 启动编排：闪屏秒开 → 拉起后端 → 主窗口就绪后切换；失败留在闪屏错误态 */
async function bootstrap(): Promise<void> {
  createTray()
  createSplash()
  try {
    await startBackend()
    await createWindow()
    // 自动更新：启动延迟首检 + 驻留期间每 4h 复检；窗口隐藏时发现新版弹托盘气球
    initAutoUpdater(
      () => mainWindow,
      (version) => {
        if (tradyAlive() && !mainWindow?.isVisible()) {
          tray?.displayBalloon({
            iconType: 'info',
            title: '发现新版本 Aye',
            content: `v${version} 已发布，打开主窗口即可下载更新。`,
          })
        }
      },
    )
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    showSplashError(message)
  }
}

// 单实例锁：第二实例直接退出，并把驻留中的主窗口唤起
const gotSingleInstanceLock = app.requestSingleInstanceLock()
if (!gotSingleInstanceLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    showMainWindow()
  })

  app.whenReady().then(() => {
    void bootstrap()
  })
}

// 关窗后不退出：托盘驻留，后端继续跑
app.on('window-all-closed', () => {
  // macOS 默认驻留 Dock；Win/Linux 由托盘承载生命周期，均不退出
})

/** before-quit 是唯一真退出通道：preventDefault 先优雅停后端，再 app.exit */
let quitFinalized = false
app.on('before-quit', (e) => {
  if (quitFinalized) return
  e.preventDefault()
  if (!isQuitting) isQuitting = true
  try { splashWindow?.close() } catch { /* ignore */ }
  void gracefulStopBackend().finally(() => {
    quitFinalized = true
    try { tray?.destroy() } catch { /* ignore */ }
    // 更新安装场景必须走正常 quit 流程：electron-updater 在 'quit' 事件里拉起安装包，
    // app.exit() 会跳过该事件；第二次 before-quit 因 quitFinalized 直接放行。
    if (isUpdateInstallPending()) {
      app.quit()
    } else {
      app.exit(0)
    }
  })
})

app.on('activate', () => {
  // 窗口只是被隐藏时 getAllWindows 仍能查到，需要主动 show
  if (mainWindow) {
    showMainWindow()
  } else if (BrowserWindow.getAllWindows().length === 0) {
    void createWindow()
  }
})

ipcMain.handle('aye:get-backend-info', () => {
  return { port: backendPort, token: apiToken }
})

ipcMain.handle('aye:get-backend-state', () => ({
  phase: backendPhase,
  detail: backendDetail,
}))

/** 崩溃/错误后的手动重试：活着或启动中一律忽略，防止双开后端 */
ipcMain.handle('aye:restart-backend', async () => {
  if (startInProgress) return { ok: false, reason: 'starting' }
  if (externalBackend) return { ok: false, reason: 'external' }
  if (backendProcess && backendProcess.exitCode === null && !backendProcess.killed) {
    return { ok: false, reason: 'running' }
  }
  restartCount = 0
  try {
    await startBackend()
    return { ok: true }
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : String(e) }
  }
})

/**
 * 为本机路径颁发来源凭证：仅用户经原生对话框/拖拽确认的路径才会被渲染端请求签名，
 * 后端按同一 AYE_API_TOKEN 验签后才允许注册为附件/工作区。
 * dev 直连模式（apiToken 为空，后端不启用校验）返回空串。
 */
ipcMain.handle('aye:grant-local-path', async (_event, p: string) => {
  if (typeof p !== 'string' || !p) return ''
  if (!apiToken) return ''
  return 'hmac1.' + createHmac('sha256', apiToken).update(p, 'utf8').digest('hex')
})

ipcMain.handle('aye:set-credential', async (_event, account: string, password: string) => {
  const creds = readCredentials()
  if (safeStorage.isEncryptionAvailable()) {
    const encrypted = safeStorage.encryptString(password)
    creds[account] = encrypted.toString('base64')
  } else {
    throw new Error('系统凭据加密不可用，无法保存密码。请仅在当前连接中使用密码。')
  }
  writeCredentials(creds)
})

ipcMain.handle('aye:get-credential', async (_event, account: string) => {
  const creds = readCredentials()
  const value = creds[account]
  if (!value) return null
  if (safeStorage.isEncryptionAvailable()) {
    return safeStorage.decryptString(Buffer.from(value, 'base64'))
  }
  throw new Error('系统凭据加密不可用，无法读取保存的密码。请重新输入连接密码。')
})

ipcMain.handle('aye:delete-credential', async (_event, account: string) => {
  const creds = readCredentials()
  delete creds[account]
  writeCredentials(creds)
})

// 窗口控制（无边框窗口需要自定义按钮）
ipcMain.handle('aye:window-minimize', () => {
  mainWindow?.minimize()
})

ipcMain.handle('aye:window-maximize', () => {
  if (mainWindow?.isMaximized()) {
    mainWindow.unmaximize()
  } else {
    mainWindow?.maximize()
  }
})

ipcMain.handle('aye:window-close', () => {
  mainWindow?.close()
})

ipcMain.handle('aye:window-is-maximized', () => {
  return mainWindow?.isMaximized() ?? false
})

// ── 本地文件能力 ──

ipcMain.handle('aye:select-workspace', async () => {
  if (!mainWindow) return null
  const r = await dialog.showOpenDialog(mainWindow, {
    title: '选择本地工作区目录',
    properties: ['openDirectory'],
  })
  return r.canceled ? null : r.filePaths[0] ?? null
})

ipcMain.handle('aye:select-files', async () => {
  if (!mainWindow) return []
  const r = await dialog.showOpenDialog(mainWindow, {
    title: '选择文件',
    properties: ['openFile', 'multiSelections'],
  })
  return r.canceled ? [] : r.filePaths
})

ipcMain.handle('aye:read-file-range', async (_event, p: string, start: number, end: number) => {
  // p 来自渲染进程，必须是绝对路径且存在
  if (typeof p !== 'string' || !p || !existsSync(p)) {
    throw new Error('文件不存在')
  }
  return await new Promise<Buffer>((resolve, reject) => {
    const chunks: Buffer[] = []
    const stream = createReadStream(p, { start, end: end - 1 })
    stream.on('data', (c: Buffer) => chunks.push(c))
    stream.on('end', () => resolve(Buffer.concat(chunks)))
    stream.on('error', reject)
  })
})

ipcMain.handle('aye:stat-local', async (_event, p: string) => {
  try {
    const st = statSync(p)
    return { size: st.size, mtime: st.mtimeMs }
  } catch {
    return null
  }
})

/**
 * 归档工作区目录：
 * - 产物写到 os.tmpdir()/aye-archives/ws-<ts>.zip|tar.gz；
 * - excludes 已在渲染端转成 minimatch glob（如任意层级的 node_modules 目录）；
 * - 通过 archive-progress 事件推进度。
 */
ipcMain.handle(
  'aye:archive-workspace',
  async (event, dir: string, format: 'zip' | 'tar.gz', excludes: string[]) => {
    if (typeof dir !== 'string' || !existsSync(dir)) {
      throw new Error('工作区目录不存在')
    }
    const outDir = join(os.tmpdir(), 'aye-archives')
    mkdirSync(outDir, { recursive: true })
    const suffix = format === 'zip' ? 'zip' : 'tar.gz'
    const out = join(outDir, `ws-${Date.now()}.${suffix}`)

    return await new Promise<{ path: string; size: number }>((resolve, reject) => {
      const output = createWriteStream(out)
      const archive = archiver(format === 'zip' ? 'zip' : 'tar', {
        zlib: { level: 6 },
        gzip: format === 'tar.gz',
      })

      output.on('close', () => resolve({ path: out, size: archive.pointer() }))
      output.on('error', reject)
      archive.on('error', reject)

      let entries = 0
      archive.on('entry', () => { entries += 1 })
      archive.on('progress', (pr: { fs: { processedBytes: number; totalBytes: number } }) => {
        event.sender.send('aye:archive-progress', {
          processedBytes: pr.fs.processedBytes,
          totalBytes: pr.fs.totalBytes,
          entries,
        })
      })

      archive.pipe(output)
      // glob 遍历：dot 文件包含、按 excludes 过滤；不把根目录名打进包
      archive.glob('**/*', {
        cwd: dir,
        dot: true,
        ignore: excludes,
      })
      void archive.finalize()
    })
  },
)

/**
 * 删除归档上传后的本地产物（仅允许 os.tmpdir()/aye-archives 内的文件，
 * 路径白名单校验，防止渲染端借 IPC 删除任意文件）。
 */
ipcMain.handle('aye:cleanup-archive', async (_event, p: string) => {
  if (typeof p !== 'string' || !p) return false
  const archiveDir = resolve(os.tmpdir(), 'aye-archives')
  const target = resolve(p)
  if (target !== archiveDir && !target.startsWith(archiveDir + sep)) return false
  try {
    await rm(target, { force: true })
    return true
  } catch {
    return false
  }
})
