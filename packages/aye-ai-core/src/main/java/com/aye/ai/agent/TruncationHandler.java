package com.aye.ai.agent;

import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * max_tokens 截断的 4 层防御处理器（对应 DeepWork 的 {@code loop-policies.ts}）。
 *
 * <p>L1 参数截断统一替换 arguments 为 '{}' → L2 纯正文截断锚点压缩 → L3 空 assistant 兜底
 * → L4 翻倍重试 / 超过次数报错。
 */
public final class TruncationHandler {

    public enum NextAction {
        CONTINUE, RETRY_TEXT, RETRY_TOOL, ERROR_TEXT_TOO_MANY, ERROR_TOOL_TOO_MANY
    }

    /** 单次处理结果 */
    public record Result(
            LlmMessage assistantMsg,
            NextAction nextAction,
            List<LlmMessage> toolErrorMessages,
            String errorUserFacing,
            int nextMaxOutputTokens
    ) {}

    private static final String TRUNCATION_ERROR_MSG =
            "模型输出被 max_tokens 截断，工具参数不完整，已安全跳过执行。请减少输出长度或拆分成更小的调用重试。";
    private static final String EMPTY_ASSISTANT_FALLBACK =
            "[⚠️ 系统标记：模型本轮输出被 max_tokens 截断，未生成任何可用内容。下一轮请补充完整回答，或改用工具调用方式分步完成任务。]";
    private static final int MAX_RETRY_COUNT = 3;

    private final int limit;
    private int currentTokens;
    private int retries = 0;

    public TruncationHandler(int initialMaxOutputTokens) {
        this.limit = initialMaxOutputTokens * 4;
        this.currentTokens = initialMaxOutputTokens;
    }

    public int currentMaxOutputTokens() {
        return currentTokens;
    }

    public Result processAssistantOutput(String assistantContent, List<LlmToolCall> toolCalls, String stopReason) {
        boolean hasContent = assistantContent != null && !assistantContent.isEmpty();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

        // L1：参数截断 → arguments 统一替换为 '{}'
        List<LlmToolCall> finalToolCalls = toolCalls;
        if ("length".equals(stopReason) && hasToolCalls) {
            finalToolCalls = new ArrayList<>();
            for (LlmToolCall tc : toolCalls) {
                finalToolCalls.add(new LlmToolCall(tc.id(), tc.name(), "{}"));
            }
        }

        // L2：纯正文截断 → 锚点压缩
        String storedContent = assistantContent == null ? "" : assistantContent;
        boolean isTextOnlyTruncation = "length".equals(stopReason) && hasContent && !hasToolCalls;
        if (isTextOnlyTruncation) storedContent = truncateContentForAnchor(storedContent);

        // L3：空 assistant → 非空兜底
        boolean hasFinalToolCalls = finalToolCalls != null && !finalToolCalls.isEmpty();
        if ("length".equals(stopReason) && storedContent.isEmpty() && !hasFinalToolCalls) {
            storedContent = EMPTY_ASSISTANT_FALLBACK;
        }

        LlmMessage assistantMsg = LlmMessage.assistant(
                storedContent.isEmpty() ? null : storedContent, hasFinalToolCalls ? finalToolCalls : null);

        // L4：截断重试 / 超过次数报错
        if ("length".equals(stopReason) && !hasToolCalls) {
            retries++;
            if (retries > MAX_RETRY_COUNT) {
                return new Result(assistantMsg, NextAction.ERROR_TEXT_TOO_MANY, null,
                        "模型输出连续 " + retries + " 次被 max_tokens 截断（纯正文阶段），可能内容过大。建议拆分成更小的问题重试。",
                        currentTokens);
            }
            currentTokens = Math.min(currentTokens * 2, limit);
            return new Result(assistantMsg, NextAction.RETRY_TEXT, null, null, currentTokens);
        }

        if ("length".equals(stopReason) && hasToolCalls) {
            retries++;
            if (retries > MAX_RETRY_COUNT) {
                return new Result(assistantMsg, NextAction.ERROR_TOOL_TOO_MANY, null,
                        "模型输出连续 " + retries + " 次被 max_tokens 截断，可能任务过大。建议拆分成更小的步骤重试。",
                        currentTokens);
            }
            currentTokens = Math.min(currentTokens * 2, limit);
            List<LlmMessage> toolErrorMessages = new ArrayList<>();
            for (LlmToolCall call : toolCalls) {
                toolErrorMessages.add(LlmMessage.tool(call.id(), TRUNCATION_ERROR_MSG));
            }
            return new Result(assistantMsg, NextAction.RETRY_TOOL, toolErrorMessages, null, currentTokens);
        }

        return new Result(assistantMsg, NextAction.CONTINUE, null, null, currentTokens);
    }

    /** 纯正文截断锚点优化：头 + 省略号 + 尾 + 极简 [truncated] */
    static String truncateContentForAnchor(String originalContent) {
        int len = originalContent.length();
        final int SHORT_THRESHOLD = 250;
        if (len < SHORT_THRESHOLD) return originalContent + "\n\n[truncated]";

        final int MIDDLE_THRESHOLD = 400;
        int headLen;
        int tailLen;
        if (len <= MIDDLE_THRESHOLD) {
            headLen = Math.min(80, (int) Math.floor(len * 0.2));
            tailLen = Math.min(200, (int) Math.floor(len * 0.3));
        } else {
            headLen = Math.min(50, (int) Math.floor(len * 0.1));
            tailLen = Math.min(150, (int) Math.floor(len * 0.25));
        }
        headLen = Math.max(headLen, 10);
        tailLen = Math.max(tailLen, 10);
        return originalContent.substring(0, headLen) + "\n...\n"
                + originalContent.substring(len - tailLen) + "\n[truncated]";
    }
}
