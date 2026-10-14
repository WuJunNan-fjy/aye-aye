<p align="left">
  <img src="apps/aye-desktop/build/icon-256.png" width="84" alt="aye-aye" />
</p>

# aye-aye

[![CI](../../../actions/workflows/ci.yml/badge.svg)](../../../actions/workflows/ci.yml)
[![GitHub Release](https://img.shields.io/github/v/release/WuJunNan-fjy/aye-aye?include_prereleases)](https://github.com/WuJunNan-fjy/aye-aye/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> 简称 **aye** —— AI 驱动的 Linux 服务器运维管理桌面应用。

通过自然语言对话管理远程服务器：SSH 终端、SFTP 文件管理、AI Agent 自动化运维、本地项目一键部署、全量操作审计。**无云端账号、无遥测，凭据本地加密，LLM 请求由客户端直发模型提供商。**

## 下载

**[⬇ 从 GitHub Releases 下载 Windows 安装包](https://github.com/WuJunNan-fjy/aye-aye/releases/latest)**（最新版自动跳转）

- 支持 Windows 10 / 11（x64），安装包内置裁剪版 JRE，**无需安装 Java**
- 首发版本未做代码签名，首次安装如出现 SmartScreen 蓝色提示：**「更多信息」→「仍要运行」**
- macOS / Linux 暂不提供安装包，可从源码自行构建（见下文）
- 应用内自动更新：新版本发布后启动时提示，用户确认后才下载安装（不经云端、无强制升级）

## 功能一览

- **AI 对话运维**：自研 Agent Loop（纯 Java，零 AI 框架），SSE 流式输出、工具调用卡片、可中断、中途插话、死循环熔断
- **三档权限**：只读 / 写操作确认 / 全部确认，每次工具执行全量审计可追溯
- **SSH 终端**：多主机管理、xterm.js 交互式 shell、TOFU 主机密钥信任
- **SFTP 文件管理**：文件树、在线编辑、200MB 分片上传、拖拽传输
- **小白一键部署**：项目识别 → 本机构建 → SFTP 上传 → 远程启动探活，全流程可视化阶段卡片
- **可观测性**：操作日志、Agent Trace、会话轨迹时间线，事件溯源、重启不丢

## 架构概览

后端为 **Java（Spring Boot 薄壳）**，AI 核心是**零框架自研 Agent Loop**（翻译自 DeepWork `packages/ai`），随 Electron 以 sidecar 方式启动。

```
┌──────────────────────────────────────────────────────────┐
│                     Electron 桌面壳                        │
│  ┌──────────────┐  ┌─────────────────────────────────┐   │
│  │  React 前端  │  │  Java 后端 (Spring Boot sidecar) │   │
│  │  (Vite)      │◄►│  REST API + SSE + WebSocket      │   │
│  │  xterm.js    │  │  ┌────────────────────────────┐  │   │
│  └──────────────┘  │  │ aye-ai-core（纯 Java）  │  │   │
│                    │  │ 自研 AgentLoop + ToolRegistry│  │   │
│                    │  │ PermissionEngine + Audit    │  │   │
│                    │  └────────────────────────────┘  │   │
│                    │  SSH/SFTP (JSch)   SQLite 单机   │   │
│                    └─────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

| 模块 | 技术栈 | 目录 |
|------|--------|------|
| AI 核心 | 纯 Java 17 + Jackson（零 AI 框架，自研 loop） | `packages/aye-ai-core/` |
| 后端 | Spring Boot 3.4 + JSch + sqlite-jdbc | `apps/aye-server/` |
| 前端 | React 18 + Ant Design + xterm.js + Zustand | `apps/aye-frontend/` |
| 桌面 | Electron 35 + electron-vite + electron-builder | `apps/aye-desktop/` |

## 项目结构

```
aye-aye/
├── pom.xml                       # aye-parent（Maven 聚合根）
├── packages/
│   └── aye-ai-core/         # aye-ai-core（纯 Java 库，仅依赖 Jackson）
│       └── src/main/java/com/aye/ai/
│           ├── agent/            # AgentLoop / AgentEventSink
│           ├── event/            # AgentEvent（sealed 判别联合）
│           ├── llm/              # LlmClient（HttpClient 流式 SSE）
│           ├── message/          # UIMessage / UIMessageToLlm
│           ├── permission/       # PermissionEngine / TrustLevel / PermissionDecision
│           ├── audit/            # AuditLedger / AuditRecord
│           ├── tool/             # Tool / ToolRegistry / ToolExecutor / JsonSchema
│           ├── tools/            # 内置工具（read_file/write_file/edit_file/...）
│           └── util/
├── apps/
│   ├── aye-server/          # Spring Boot 薄壳
│   │   └── src/main/java/com/aye/server/
│   │       ├── chat/             # SSE 聊天编排（ChatController/ChatService）
│   │       ├── controller/       # REST 控制器（hosts/ssh/sftp/audit/sessions/models/...）
│   │       ├── ssh/              # SshSessionManager / SftpService / SshToolSupport / SSH+SFTP AI 工具
│   │       ├── storage/          # Database + 各 Store（SQLite）
│   │       ├── ws/               # 交互式终端 WebSocket（/ws/terminal/:sessionId）
│   │       ├── config/           # Bean 装配 / AppProperties
│   │       └── web/              # 统一异常 {statusCode, message, code}
│   ├── aye-frontend/     # React 前端（Vite）
│   └── aye-desktop/         # Electron 壳（spawn java -jar sidecar）
```

## 环境要求

- **JDK 17**（后端编译与运行，开发机可配 `JAVA_HOME`）
- Maven 无需预装（仓库自带 Maven Wrapper，首次运行自动下载 3.9.9）
- **Node.js** >= 18 + **pnpm**（前端）
- **npm**（桌面端）

## 快速开始

### 1. 构建后端

```powershell
$env:MAVEN_OPTS="-Dfile.encoding=UTF-8"
.\mvnw.cmd -pl apps/aye-server -am package -DskipTests
# 产物：apps/aye-server/target/aye-server-0.0.1-SNAPSHOT.jar（fat jar）
```

### 2. 启动后端

```bash
java -jar apps/aye-server/target/aye-server-0.0.1-SNAPSHOT.jar --server.port=8080
```

启动成功后输出 `Aye backend running on http://127.0.0.1:8080`。

常用环境变量（Electron sidecar 自动注入，手动运行可选）：

| 变量 | 说明 |
|------|------|
| `PORT` | 监听端口（0 = 随机） |
| `AYE_DATA_DIR` | 数据目录（SQLite 存放处，默认 `~/.aye`） |
| `AYE_API_TOKEN` | API token（WebSocket 终端握手校验） |

模型与 API Key 不再走 `.env`，在应用「模型设置」里配置（`/api/models` 增删改查，key 脱敏存储于 SQLite）。

### 3. 启动前端与桌面端

```bash
# 前端
cd apps/aye-frontend && pnpm install && pnpm dev

# 桌面端（自动探测 5173-5178 的前端 dev server；若 8080 已有后端则复用，否则 spawn java sidecar）
cd apps/aye-desktop && npm install && npm run dev
```

## API 接口

统一前缀 `/api`，非 2xx 响应体为 `{"statusCode","message","code"}`。

| 分组 | 路径 | 说明 |
|------|------|------|
| 主机 | `/api/hosts` | CRUD + 拖拽排序 |
| SSH | `/api/ssh/connect\|exec\|disconnect` | 长效会话 + 一次性命令 |
| SFTP | `/api/sftp/*` | list/read/write/download/upload/remove |
| 终端 | `ws://…/ws/terminal/:sessionId?token=` | 交互式 shell（文本帧双向透传，`__ping__`/`__pong__` 心跳） |
| 会话 | `/api/sessions` | 聊天会话与历史消息（wire 格式 `{role, parts}`） |
| Agent | `POST /api/agent/chat/stream` | AI 对话（SSE 流式） |
| Agent 模式 | `/api/agent/mode` | readonly / write_confirm / all_confirm |
| 模型 | `/api/models` | 模型 CRUD + key 脱敏（hasKey） |
| 审计 | `/api/audit`、`/stats`、`/tools` | 工具调用审计 |
| 快捷指令 | `/api/quick-commands` | 全局/按主机 |
| 设置 | `/api/settings` | 应用设置（kv） |

### SSE 事件格式（`/api/agent/chat/stream`）

```
start(sessionId) → (thinking | text | tool_call | tool_result)* → (error | done)
```

请求体：`{ sessionId?, message, modelId?, hostId?, mode? }`。

## Agent 权限模型

`PermissionEngine` 在每次工具执行前评估（接线于 `ToolExecutor`）：

| 决策 | 行为 |
|------|------|
| Allow | 放行执行 |
| Deny | 阻断并返回错误原因 |
| Confirm | 暂停工具执行，等待用户通过审批卡批准或拒绝；超时或停止任务按拒绝处理 |

所有 Allow/Deny/Confirm 决策均写入审计账本。

只读模式直接拒绝修改性操作；写操作确认模式允许读取，写入、部署与构建均需审批；全部确认模式下读取也需审批。
`ask_user` 是用户问答交互，不额外要求一次工具审批。写操作确认模式可选择记住普通操作，
授权仅匹配同一会话、模式、工作区和完整参数，模式或工作区变化后失效；全部确认及高风险操作每次都需审批。
聊天读取设置中保存的默认模式，任务运行期间不能切换模式；设置里的默认值变更用于下一轮任务。

## 构建与打包

### 后端

`./mvnw -pl apps/aye-server -am package -DskipTests`（Windows 用 `.\mvnw.cmd`）→ fat jar。

### 前端

`cd apps/aye-frontend && pnpm build`。

### 桌面端

```bash
cd apps/aye-desktop
npm run dist    # 自动 prepare-backend（mvnw package + jlink）→ electron-vite build → NSIS 安装包
```

`npm run dist` 会先执行 `scripts/prepare-backend.cjs`（构建机只需 JDK 17+，Maven 由仓库内 `mvnw` 提供；普通用户机器无需 Java 环境）：

- `mvnw package` 产出 fat jar，复制为 `resources/backend/server.jar`（extraResources，asar 外）
- jlink 裁剪 JRE 到 `resources/runtime/bin/java.exe`（含 `jdk.crypto.ec`）；jlink 只能产出**构建机所在平台**的运行时，因此官方发行包目前只在 Windows runner 上构建、仅提供 Windows x64
- 布局只此一种约定；开发态找不到内置 runtime 时回退 `AYE_JAVA` / `JAVA_HOME` / PATH

## 数据存储

| 数据 | 存储位置 | 说明 |
|------|----------|------|
| 会话/消息（聊天记录） | SQLite（`AYE_DATA_DIR/aye.db`） | 按 sessionId，含轻量 schema 迁移 |
| 主机 / 审计 / 设置 / 快捷指令 / 模型+key | SQLite（同上） | 持久化 |
| SSH 凭据 | Electron safeStorage | 系统级加密 |

## License

MIT
