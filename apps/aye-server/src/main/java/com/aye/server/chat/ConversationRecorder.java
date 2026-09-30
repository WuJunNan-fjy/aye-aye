package com.aye.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.event.AgentEvent;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 AgentEvent 流里重建「本轮产生的 assistant / tool 消息」。
 *
 * <p>AgentLoop 的事件序列（多步工具往返）：
 * <pre>
 *   [context*] [thinking*] text* [tool_call*] [interaction?] [tool_result*]
 *   [text* [tool_call*] [interaction?] [tool_result*]]* done
 * </pre>
 * 重建为（不含用户消息，用户消息由调用方自行追加）：
 * <pre>
 *   assistant( reasoning* + text + tool_call* + interaction* )
 *   tool( tool_result* )
 *   ...（下一轮往返）...
 *   assistant( text )
 * </pre>
 * 与 {@link com.aye.ai.message.UIMessageToLlm} 的扁平化规则严格对齐。
 *
 * <p>parts 严格按事件到达顺序排列（同类文本增量合并），保证历史重放的时间线
 * 与实时流式一致：思考块、工具行、人工确认/提问卡都落在正确的位置。
 */
public final class ConversationRecorder {

    private final List<UIMessage> out = new ArrayList<>();

    /** 当前 assistant 段的有序 parts（reasoning/text/context/tool_call/interaction）。 */
    private final List<UIPart> assistantParts = new ArrayList<>();
    /** 当前 tool 消息的 tool_result parts。 */
    private final List<UIPart> toolParts = new ArrayList<>();
    private boolean inToolResults = false;

    public void accept(AgentEvent event) {
        if (event instanceof AgentEvent.ContextInjectedEvent ci) {
            closeToolIfNeeded();
            assistantParts.add(new UIPart.ContextPart(ci.name(), ci.summary() == null ? "" : ci.summary()));
        } else if (event instanceof AgentEvent.ThinkingEvent th) {
            closeToolIfNeeded();
            mergeText(th.content(), true);
        } else if (event instanceof AgentEvent.TextDeltaEvent t) {
            closeToolIfNeeded();
            mergeText(t.content(), false);
        } else if (event instanceof AgentEvent.ToolCallEvent tc) {
            closeToolIfNeeded();
            assistantParts.add(new UIPart.ToolCallPart(tc.id(), tc.toolName(), tc.input()));
        } else if (event instanceof AgentEvent.ToolResultEvent tr) {
            if (!inToolResults) {
                flushAssistant();
                inToolResults = true;
            }
            toolParts.add(new UIPart.ToolResultPart(tr.id(), tr.output(), tr.isError()));
        }
        // ErrorEvent / DoneEvent 不参与消息重建
    }

    /**
     * 人工确认决议产生后记录（批准/拒绝）。位置紧跟被确认工具的 tool_call：
     * 回调在工具执行前阻塞返回，此时 tool_result 尚未产生。
     */
    public void confirmResolved(String confirmId, String toolName, String message,
                                List<String> tags, JsonNode input, boolean approved) {
        closeToolIfNeeded();
        assistantParts.add(new UIPart.InteractionConfirmPart(
                confirmId, toolName, message,
                tags == null ? List.of() : tags, input, approved));
    }

    /**
     * ask_user 决议产生后记录。{@code answer} 为 null/空白表示用户跳过。
     */
    public void askResolved(String askId, String question, String hint,
                            List<String> options, String answer) {
        closeToolIfNeeded();
        boolean skipped = answer == null || answer.isBlank();
        assistantParts.add(new UIPart.InteractionAskPart(
                askId, question, hint,
                options == null ? List.of() : options,
                skipped ? null : answer, skipped));
    }

    /** 流结束：把最后一撮结果落盘 */
    public void finish() {
        closeToolIfNeeded();
        flushAssistant();
    }

    /** 返回重建出的消息（不含用户消息） */
    public List<UIMessage> messages() {
        return out;
    }

    /**
     * 把文本增量合并进当前段末尾的同类 part；末尾不是同类 part 则新建。
     * @param reasoning true=思考（ReasoningPart），false=正文（TextPart）
     */
    private void mergeText(String delta, boolean reasoning) {
        if (delta == null || delta.isEmpty()) return;
        UIPart last = assistantParts.isEmpty() ? null : assistantParts.get(assistantParts.size() - 1);
        if (reasoning && last instanceof UIPart.ReasoningPart r) {
            assistantParts.set(assistantParts.size() - 1, new UIPart.ReasoningPart(r.text() + delta));
        } else if (!reasoning && last instanceof UIPart.TextPart t) {
            assistantParts.set(assistantParts.size() - 1, new UIPart.TextPart(t.text() + delta));
        } else {
            assistantParts.add(reasoning
                    ? new UIPart.ReasoningPart(delta)
                    : new UIPart.TextPart(delta));
        }
    }

    private void closeToolIfNeeded() {
        if (inToolResults) {
            out.add(new UIMessage("tool", new ArrayList<>(toolParts)));
            toolParts.clear();
            inToolResults = false;
        }
    }

    private void flushAssistant() {
        if (assistantParts.isEmpty()) return;
        out.add(new UIMessage("assistant", new ArrayList<>(assistantParts)));
        assistantParts.clear();
    }
}
