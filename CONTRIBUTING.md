# 贡献指南

感谢关注 aye-aye。

## 先读产品边界

aye-aye 的定位见 README 与 `docs/2026-09-21-产品目标与开源路线.md`。以下方向**明确不做**，相关 PR 不会被合并：

- 云端 SaaS / Web 运维平台 / 多租户
- Coding Agent（写业务代码、IDE 集成）
- MCP 服务器、多 Agent 编排、Docker 编排
- 任何绕过 PermissionEngine 或审计账本的通道

不确定你的想法是否在范围内时，先发 Issue / Discussion 讨论，避免白做。

## 开发环境

- JDK 17、Node.js >= 18、pnpm 10（前端）、npm（桌面端）
- 无需预装 Maven：仓库自带 Maven Wrapper（`./mvnw` / `mvnw.cmd`）

```bash
# 后端测试
./mvnw test                    # Windows: .\mvnw.cmd test

# 前端类型与构建
cd apps/aye-frontend && pnpm install && pnpm build

# 桌面端开发
cd apps/aye-desktop && npm install && npm run dev
```

## 提交 PR 前

- 后端改动：`mvnw test` 全绿（ai-core + server）
- 前端改动：`pnpm build` 通过（含 `tsc -b`）
- 行为变更请同步更新对应文档；用户可见变更在 `CHANGELOG.md` 的 Unreleased 段加一行
- 提交信息使用 Conventional Commits：`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:`
- 一个 PR 只做一件事，保持 diff 聚焦

## 代码约定

- Java 17，包结构按层（controller/service/store），核心能力下沉 `packages/aye-ai-core`，
  ai-core 保持零 AI 框架（仅 Jackson）
- 前端 SFC 风格以现有代码为准；新增 UI 遵循黑白极简风格
- 不引入新的重型依赖，尤其是 Spring AI / LangChain 类框架
