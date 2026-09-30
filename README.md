<p align="left">
  <img src="apps/aye-desktop/build/icon-256.png" width="84" alt="aye-aye" />
</p>

# aye-aye

> 简称 **aye** —— AI 驱动的 Linux 服务器运维管理桌面应用。

通过自然语言对话管理远程服务器，支持 SSH 终端、SFTP 文件管理、AI Agent 自动化运维、审计日志等功能。

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
| 前端 | React 18 + Ant Design + xterm.js + Zustand | `apps/aye-frontend-v2/` |
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
│   ├── aye-frontend-v2/     # React 前端（Vite）
│   └── aye-desktop/         # Electron 壳（spawn java -jar sidecar）
```

## 环境要求

- **JDK 17**（后端编译与运行，开发机可配 `JAVA_HOME`）
- **Maven 3.9+**（后端构建）
- **Node.js** >= 18 + **pnpm**（前端）
- **npm**（桌面端）

## 快速开始

### 1. 构建后端

```powershell
$env:MAVEN_OPTS="-Dfile.encoding=UTF-8"
mvn -pl apps/aye-server -am package -DskipTests
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
cd apps/aye-frontend-v2 && pnpm install && pnpm dev

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
| Confirm | 当前版本（SSE 单向流无交互确认通道）保守拒绝 |

所有 Allow/Deny/Confirm 决策均写入审计账本。

## 构建与打包

### 后端

`mvn -pl apps/aye-server -am package -DskipTests` → fat jar。

### 前端

`cd apps/aye-frontend-v2 && pnpm build`。

### 桌面端

```bash
cd apps/aye-desktop
npm run dist    # 自动 prepare-backend（mvn package + jlink）→ electron-vite build → NSIS 安装包
```

`npm run dist` 会先执行 `scripts/prepare-backend.cjs`（构建机需 JDK 17+ 与 Maven，普通用户机器无需 Java 环境）：

- `mvn package` 产出 fat jar，复制为 `resources/backend/server.jar`（extraResources，asar 外）
- jlink 裁剪 JRE 到 `resources/runtime/bin/java.exe`（含 `jdk.crypto.ec`，构建机跨平台生成）
- 布局只此一种约定；开发态找不到内置 runtime 时回退 `AYE_JAVA` / `JAVA_HOME` / PATH

## 数据存储

| 数据 | 存储位置 | 说明 |
|------|----------|------|
| 会话/消息（聊天记录） | SQLite（`AYE_DATA_DIR/aye.db`） | 按 sessionId，含轻量 schema 迁移 |
| 主机 / 审计 / 设置 / 快捷指令 / 模型+key | SQLite（同上） | 持久化 |
| SSH 凭据 | Electron safeStorage | 系统级加密 |

## License

MIT
