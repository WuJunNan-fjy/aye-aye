# 贡献指南

感谢关注 aye-aye。

## 先读产品边界

aye-aye 面向单机桌面上的 Linux 服务器管理，定位与当前功能见 README。以下方向**明确不做**，相关 PR 不会被合并：

- 云端 SaaS / Web 运维平台 / 多租户
- Coding Agent（写业务代码、IDE 集成）
- MCP 服务器、多 Agent 编排、Docker 编排
- 任何绕过 PermissionEngine 或审计账本的通道

不确定你的想法是否在范围内时，先发 Issue / Discussion 讨论，避免白做。

## 开发环境

- JDK 17、Node.js >= 22.12、pnpm 10（前端）、npm（桌面端）
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
- 前端只维护 `apps/aye-frontend/pnpm-lock.yaml`，桌面端只维护 `apps/aye-desktop/package-lock.json`；不要提交另一包管理器生成的锁文件。
- 引入第三方源码、样式或资源时保留原有版权和许可声明，并更新 `THIRD_PARTY_NOTICES.md`。

## 代码约定

准备独立公开副本时，可在仓库根运行 `powershell -NoProfile -File scripts/prepare-public-source.ps1`。脚本将跟踪文件与未忽略的新文件复制到新的 `.opensource/` 子目录，不携带 Git 历史、不覆盖已有副本。它只做有限的敏感文件和密钥模式检查；公开前仍需人工审查及确认历史凭据已失效。

- Java 17，包结构按层（controller/service/store），核心能力下沉 `packages/aye-ai-core`，
  ai-core 保持零 AI 框架（仅 Jackson）
- 前端 SFC 风格以现有代码为准；新增 UI 遵循黑白极简风格
- 不引入新的重型依赖，尤其是 Spring AI / LangChain 类框架
