package com.aye.ai.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.util.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UIMessage[] → LLMMessage[] 转换器（对应 DeepWork 里 {@code loop.ts} 的
 * {@code convertUIMessagesToLLMMessages}）。
 *
 * <p>前端 UIMessage 是 parts 数组富格式，LLM 协议层只认 role/content/tool_calls/tool_call_id，
 * 这里做扁平化。
 *
 * <p><b>协议不变量（OpenAI / DeepSeek 强制）</b>：每条带 {@code tool_calls} 的 assistant
 * 消息之后，必须为每个 {@code tool_call_id} 恰好跟一条 tool 消息。历史可能因中止 / 异常 /
 * 冷恢复边界缺失工具结果（UIMessage 来源不可信），本转换器是最后一道防线：
 * <ul>
 *   <li>assistant 声明了 tool_call 但找不到结果 → 补一条 interrupted 合成结果；</li>
 *   <li>上一条 assistant 的调用还没闭合就遇到新消息 → 先补齐合成结果；</li>
 *   <li>找不到归属调用的 tool 结果 → 补一条只含该调用的合成 assistant 配对；</li>
 *   <li>同一 id 的重复结果只保留第一条；纯 reasoning / 交互记录等无文本 assistant 消息不下发。</li>
 * </ul>
 */
public final class UIMessageToLlm {

    /** 缺失工具结果时回填给模型的统一文案（与冷恢复 / AgentLoop 中断口径一致）。 */
    static final String INTERRUPTED_TOOL_OUTPUT =
            "会话中断，该工具调用未完成（interrupted）；请根据已有上下文重新确认后再执行。";

    private UIMessageToLlm() {}

    public static List<LlmMessage> convert(List<UIMessage> uiMessages) {
        Converter c = new Converter();
        for (UIMessage msg : uiMessages) {
            switch (msg.role()) {
                case "system" -> {
                    c.closePendingCalls();
                    String text = concatText(msg.parts());
                    if (!text.isEmpty()) c.out.add(LlmMessage.system(text));
                }
                case "user" -> {
                    c.closePendingCalls();
                    String text = concatText(msg.parts());
                    if (!text.isEmpty()) c.out.add(LlmMessage.user(text));
                }
                case "assistant" -> c.addAssistant(msg.parts());
                case "tool" -> {
                    for (UIPart p : msg.parts()) {
                        if (p instanceof UIPart.ToolResultPart trp) c.addToolResult(trp);
                    }
                }
                default -> { /* 未知角色忽略 */ }
            }
        }
        // 历史末尾悬空调用也要闭合（下一轮的 user 消息随后插入，不能带着悬空 tool_calls）
        c.closePendingCalls();
        return c.out;
    }

    /** 有状态转换器：维护「已声明、尚未回复」的 tool_call id 队列。 */
    private static final class Converter {
        final List<LlmMessage> out = new ArrayList<>();
        /** 最近一条 assistant 消息中等待 tool 响应的调用（id → name），按声明顺序。 */
        final Deque<PendingCall> pending = new ArrayDeque<>();

        void addAssistant(List<UIPart> parts) {
            // 上一条 assistant 的调用若仍悬空，先用合成结果闭合，再发新 assistant
            closePendingCalls();

            String content = concatText(parts);
            List<UIPart.ToolCallPart> callParts = new ArrayList<>();
            // id → 该 assistant parts 内自带的结果；重复 id 保留第一条
            Map<String, UIPart.ToolResultPart> ownResults = new LinkedHashMap<>();
            for (UIPart p : parts) {
                if (p instanceof UIPart.ToolCallPart tcp) {
                    callParts.add(tcp);
                } else if (p instanceof UIPart.ToolResultPart trp) {
                    ownResults.putIfAbsent(trp.id(), trp);
                }
            }
            // reasoning / interaction / context part 不送模型；无文本无调用的消息整条跳过
            if (callParts.isEmpty()) {
                if (!content.isEmpty()) out.add(LlmMessage.assistant(content, null));
                // 错放在无调用 assistant 里的 tool 结果：合成配对，不丢弃
                for (UIPart.ToolResultPart trp : ownResults.values()) {
                    emitOrphanResult(trp);
                }
                return;
            }

            List<LlmToolCall> toolCalls = new ArrayList<>();
            for (UIPart.ToolCallPart tcp : callParts) {
                toolCalls.add(new LlmToolCall(tcp.id(), tcp.toolName(), serializeArguments(tcp.input())));
                pending.addLast(new PendingCall(tcp.id(), tcp.toolName()));
            }
            out.add(LlmMessage.assistant(content.isEmpty() ? null : content, toolCalls));

            // parts 内自带的结果立即配对；没带结果的调用保持 pending —— 结果可能在
            // 下一条 role="tool" 的 UIMessage 里（ConversationRecorder 投影形状），
            // 只有到下一条消息边界（closePendingCalls）仍无应答时才合成 interrupted。
            for (UIPart.ToolCallPart tcp : callParts) {
                UIPart.ToolResultPart trp = ownResults.remove(tcp.id());
                if (trp != null) {
                    pending.removeIf(pc -> pc.id.equals(tcp.id()));
                    out.add(LlmMessage.tool(tcp.id(), serializeOutput(trp.output())));
                }
            }
            // parts 内找不到归属调用的多余结果：单独合成配对
            for (UIPart.ToolResultPart trp : ownResults.values()) {
                emitOrphanResult(trp);
            }
        }

        /** 处理 role="tool" 消息（ConversationRecorder 投影形状：结果与调用分属两条 UIMessage）。 */
        void addToolResult(UIPart.ToolResultPart trp) {
            // 优先闭合 pending 中同 id 的调用
            for (PendingCall pc : pending) {
                if (pc.id.equals(trp.id())) {
                    pending.remove(pc);
                    out.add(LlmMessage.tool(trp.id(), serializeOutput(trp.output())));
                    return;
                }
            }
            emitOrphanResult(trp);
        }

        /** 为所有悬空调用补 interrupted 合成结果。 */
        void closePendingCalls() {
            while (!pending.isEmpty()) {
                PendingCall pc = pending.pollFirst();
                out.add(LlmMessage.tool(pc.id, INTERRUPTED_TOOL_OUTPUT));
            }
        }

        /** 按 id 发一条 tool 结果；trp 为 null（缺结果 / 结果被重复 id 消耗）时补合成文案。 */
        private void emitToolResult(String id, UIPart.ToolResultPart trp) {
            pending.removeIf(pc -> pc.id.equals(id));
            out.add(LlmMessage.tool(id,
                    trp == null ? INTERRUPTED_TOOL_OUTPUT : serializeOutput(trp.output())));
        }

        /** 孤立 tool 结果：补一条只含该 id 的合成 assistant(tool_calls) 再跟 tool 消息。 */
        private void emitOrphanResult(UIPart.ToolResultPart trp) {
            // 防御：pending 中恰好有同 id 调用（理论上 addToolResult 已优先匹配）
            for (PendingCall pc : pending) {
                if (pc.id.equals(trp.id())) {
                    emitToolResult(trp.id(), trp);
                    return;
                }
            }
            out.add(LlmMessage.assistant(null, List.of(new LlmToolCall(trp.id(), "", "{}"))));
            out.add(LlmMessage.tool(trp.id(), serializeOutput(trp.output())));
        }
    }

    private record PendingCall(String id, String name) {}

    private static String concatText(List<UIPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (UIPart p : parts) {
            if (p instanceof UIPart.TextPart tp) sb.append(tp.text());
        }
        return sb.toString();
    }

    private static String serializeArguments(JsonNode input) {
        if (input == null) return "{}";
        String s = input.isTextual() ? input.asText() : Json.stringify(input);
        return s == null || s.trim().isEmpty() ? "{}" : s;
    }

    private static String serializeOutput(JsonNode output) {
        if (output == null) return "";
        if (output.isTextual()) return output.asText();
        return Json.stringify(output);
    }
}
