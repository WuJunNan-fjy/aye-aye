#!/usr/bin/env node
/**
 * 桌面端后端制品准备（打包前置步骤，npm run dist 前自动执行）：
 *   1. 在仓库根执行 mvnw package，产出 Spring Boot fat jar
 *   2. 复制 apps/aye-server/target/aye-server-*.jar → build/backend/server.jar
 *   3. 用 JAVA_HOME 下的 jlink 裁剪 JRE → build/runtime（含 jdk.crypto.ec）
 *
 * 产物布局（electron-builder extraResources 落到 resources/，只保留这一种约定）：
 *   resources/backend/server.jar
 *   resources/runtime/bin/java(.exe)
 *
 * 只依赖构建机上有 JDK 17+（含 jlink/jmods）与 Maven；普通用户机器无需任何 Java 环境。
 * 依赖发生变化后，建议用以下命令重新核对模块清单并更新 JLINK_MODULES：
 *   jdeps --print-module-deps --ignore-missing-deps -q --recursive \
 *     --multi-release 17 --class-path "BOOT-INF/lib/*" BOOT-INF/classes
 */
'use strict'

const { spawnSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DESKTOP_DIR = path.resolve(__dirname, '..')
const REPO_ROOT = path.resolve(DESKTOP_DIR, '..', '..')
const SERVER_TARGET = path.join(REPO_ROOT, 'apps', 'aye-server', 'target')
const BACKEND_OUT = path.join(DESKTOP_DIR, 'build', 'backend')
const RUNTIME_OUT = path.join(DESKTOP_DIR, 'build', 'runtime')

/**
 * jdeps（JDK 17.0.7）实测清单 + jdk.crypto.ec（HTTPS EC 握手必需，jdeps 不会提示）
 * @type {string[]}
 */
const JLINK_MODULES = [
  'java.base',
  'java.compiler',
  'java.desktop',
  'java.instrument',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.sql',
  'jdk.crypto.ec',
  'jdk.jfr',
  'jdk.unsupported',
]

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: 'inherit', shell: false, ...opts })
  if (r.error) throw r.error
  if (r.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} exited with ${r.status}`)
  }
  return r
}

function dirSizeMB(dir) {
  let bytes = 0
  const stack = [dir]
  while (stack.length) {
    const cur = stack.pop()
    for (const e of fs.readdirSync(cur, { withFileTypes: true })) {
      const p = path.join(cur, e.name)
      if (e.isDirectory()) stack.push(p)
      else bytes += fs.statSync(p).size
    }
  }
  return (bytes / 1024 / 1024).toFixed(1)
}

function resolveJdkBin(tool) {
  const exe = process.platform === 'win32' ? `${tool}.exe` : tool
  if (process.env.JAVA_HOME) {
    const p = path.join(process.env.JAVA_HOME, 'bin', exe)
    if (fs.existsSync(p)) return p
  }
  // 回退 PATH（shell:true 让系统解析 mvn/jlink）
  return exe
}

function step1_mavenPackage() {
  console.log('[prepare-backend] 1/3 mvnw package（仓库根，跳过测试）...')
  // 使用 Maven Wrapper，构建机无需预装 Maven；
  // Windows 上 mvnw.cmd 是批处理、Node 18.20+/20+ 安全策略要求 shell:true 才能 spawn；
  // 非 Windows 用 sh mvnw 调用，规避仓库检出时的可执行位问题。
  if (process.platform === 'win32') {
    run('mvnw.cmd', ['-q', '-DskipTests', 'package'], { cwd: REPO_ROOT, shell: true })
  } else {
    run('sh', ['mvnw', '-q', '-DskipTests', 'package'], { cwd: REPO_ROOT })
  }
}

function step2_copyJar() {
  console.log('[prepare-backend] 2/3 复制 fat jar → build/backend/server.jar')
  const jar = fs
    .readdirSync(SERVER_TARGET)
    .find((f) => /^aye-server-.+\.jar$/.test(f) && !f.endsWith('.original'))
  if (!jar) throw new Error(`未在 ${SERVER_TARGET} 找到 aye-server-*.jar`)

  fs.rmSync(BACKEND_OUT, { recursive: true, force: true })
  fs.mkdirSync(BACKEND_OUT, { recursive: true })
  fs.copyFileSync(path.join(SERVER_TARGET, jar), path.join(BACKEND_OUT, 'server.jar'))
  console.log(`[prepare-backend]   server.jar ${dirSizeMB(BACKEND_OUT)} MB`)
}

function step3_jlink() {
  console.log('[prepare-backend] 3/3 jlink 裁剪 JRE → build/runtime')
  const jlink = resolveJdkBin('jlink')
  fs.rmSync(RUNTIME_OUT, { recursive: true, force: true })
  // --compress=2 = zip-6（JDK17 语法；JDK21 起可用 zip-9，数值写法仍兼容）
  run(jlink, [
    '--add-modules', JLINK_MODULES.join(','),
    '--strip-debug',
    '--compress=2',
    '--no-header-files',
    '--no-man-pages',
    '--output', RUNTIME_OUT,
  ])
  const javaExe = path.join(RUNTIME_OUT, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (!fs.existsSync(javaExe)) throw new Error(`jlink 产物缺少 ${javaExe}`)
  console.log(`[prepare-backend]   runtime ${dirSizeMB(RUNTIME_OUT)} MB`)
}

try {
  step1_mavenPackage()
  step2_copyJar()
  step3_jlink()
  console.log('[prepare-backend] 完成：build/backend + build/runtime 已就绪')
} catch (e) {
  console.error('[prepare-backend] 失败：', e.message)
  process.exit(1)
}
