package com.aye.ai.message;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 前后端共享的富文本消息格式（parts 数组协议）。
 *
 * <p>对应 DeepWork 的 {@code UIMessage}：一条消息 = role + 一个 parts 数组，
 * 每个 part 是 text / tool-call / tool-result / reasoning 之一（判别联合）。
 * 前端用同一套结构渲染，后端在送入 LLM 前再扁平化成 role/content 协议。
 */
public record UIMessage(String role, List<UIPart> parts) {

    /** part 判别联合：text / reasoning / tool-call / tool-result / context（仅前端展示） */
    public sealed interface UIPart {

        record TextPart(String text) implements UIPart {}

        record ReasoningPart(String text) implements UIPart {}

        record ToolCallPart(String id, String toolName, JsonNode input) implements UIPart {}

        /**
         * 工具结果。{@code isError} 对齐 DeepWork / opencode 的落库契约：
         * 历史重放时前端据此渲染成功绿点 / 失败红点（运行时 SSE 事件本来就带该标记）。
         */
        record ToolResultPart(String id, JsonNode output, boolean isError) implements UIPart {}

        /**
         * 上下文注入（仅前端时间线展示，不送入 LLM）。
         * 对应 dsh "context-injection" part：一个被纳入本轮考虑的外部材料条目，
         * 例如 AGENTS.md、子模块 system prompt、skill-catalog 等。
         */
        record ContextPart(String name, String summary) implements UIPart {}

        /**
         * 人工确认交互记录（仅前端时间线展示，不送入 LLM）。
         * 在确认决议产生（批准/拒绝）后写入 assistant parts，位置紧跟被确认的 tool_call，
         * 历史会话重放时渲染成不可操作、可展开回顾的完成行。
         *
         * @param approved true=已批准，false=已拒绝
         */
        record InteractionConfirmPart(String confirmId, String toolName, String message,
                                      List<String> tags, JsonNode input, boolean approved)
                implements UIPart {}

        /**
         * Agent 主动提问（ask_user）交互记录（仅前端时间线展示，不送入 LLM）。
         *
         * @param answer  用户作答原文；{@code skipped=true} 时为 null（用户跳过/未答）
         * @param skipped true=用户跳过该问题
         */
        record InteractionAskPart(String askId, String question, String hint,
                                  List<String> options, String answer, boolean skipped)
                implements UIPart {}
    }
}
