package com.aye.server.chat;

/**
 * 聊天请求（POST /api/agent/chat/stream 的请求体）。
 *
 * @param sessionId   会话 ID（缺省自动生成）
 * @param message     用户本轮输入文本（必填）
 * @param provider    指定厂商 id（如 deepseek，与 modelId 搭配；缺省用默认模型）
 * @param modelId     指定模型（厂商目录里的模型 ID；缺省用默认模型）
 * @param hostId      关联主机（SSH 工具用，可选）
 * @param mode        执行模式：readonly / write_confirm / all_confirm（可选）
 * @param attachments 用户拖入的本地附件（可选；文本内容会被注入用户消息，供模型直接阅读）
 * @param workspaceId 绑定的项目（工作区）ID（可选；部署流程用，选中后本机操作仅限该目录）
 * @param deploy      部署模式标记（可选，true 时本轮注入「一键部署」7 阶段流水线提示词；
 *                    仅绑定项目但未显式进入部署模式时不注入，避免普通问答被部署指令污染）
 */
public record ChatRequest(
        String sessionId,
        String message,
        String provider,
        String modelId,
        Long hostId,
        String mode,
        java.util.List<ChatAttachment> attachments,
        Long workspaceId,
        Boolean deploy) {}
