package com.aye.ai.tool;

import com.aye.ai.llm.LlmTypes.LlmMessage;

/**
 * 单个工具的执行结果（双格式并列，对应 DeepWork 的 {@code ToolExecutionResult}）。
 *
 * @param toolMessage LLM 协议层消息（塞回 messages 给下一轮用，content 必为字符串）
 * @param rawOutput   原始工具输出（可能是对象，直接给前端 SSE 展示用）
 * @param isError     是否执行失败
 * @param toolName    对应的工具名
 */
public record ToolResult(LlmMessage toolMessage, Object rawOutput, boolean isError, String toolName) {}
