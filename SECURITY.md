# 安全策略

## 报告漏洞

请**不要**在公开 Issue 中披露安全漏洞。通过 GitHub 的私密漏洞报告提交：

**[Report a vulnerability](https://github.com/WuJunNan-fjy/aye-aye/security/advisories/new)**
（仓库 → Security → Report a vulnerability；如入口未开放，请在 Discussions 中私信维护者）

报告请包含：

- 问题描述与影响范围
- 复现步骤 / PoC
- 受影响版本
- 建议的修复方式（可选）

我会在 72 小时内确认收到，并在修复发布后公开致谢（除非你希望匿名）。

## 安全模型边界

aye-aye 的安全设计：

- 后端只绑定 `127.0.0.1`，端口随机，启动时生成 32 字节随机 token
- SSH 凭据使用 Electron safeStorage（系统级加密）保存，不明文落盘
- 主机密钥采用 TOFU（首次使用信任）策略，变更时明确告警
- 每次 AI 工具执行都经过 PermissionEngine 评估，Allow/Deny/Confirm 全量写审计账本
- 应用无云端账号、无遥测、不收集用户数据；LLM 请求由客户端直发模型提供商

**不在范围内**：用户主动授予 `all_confirm` 模式后 AI 执行命令造成的破坏；用户自行配置的模型提供商侧的数据处理。
