package com.aye.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.event.AgentEvent;
import com.aye.ai.util.Json;

/**
 * AgentEvent → 前端 SSE 的 JSON wire 格式（判别联合加 {@code type} 字段）。
 *
 * <p>对应 DeepWork 的 AgentEvent 判别联合 + UIMessage parts 协议：
 * <pre>
 *   {"type":"context_injected","name":"...","summary":"..."}
 *   {"type":"thinking",          "content":"..."}
 *   {"type":"text",              "content":"..."}
 *   {"type":"tool_call",         "id","toolName","input"}
 *   {"type":"tool_result",       "id","toolName","output","isError"}
 *   {"type":"error",             "message":"..."}
 *   {"type":"done"}
 * </pre>
 */
public final class AgentEventJson {

    private AgentEventJson() {}

    public static JsonNode toJson(AgentEvent event) {
        if (event instanceof AgentEvent.ContextInjectedEvent ci) {
            return Json.object()
                    .put("type", "context_injected")
                    .put("name", ci.name())
                    .put("summary", ci.summary() == null ? "" : ci.summary());
        }
        if (event instanceof AgentEvent.ThinkingEvent t) {
            return Json.object().put("type", "thinking").put("content", t.content());
        }
        if (event instanceof AgentEvent.TextDeltaEvent t) {
            return Json.object().put("type", "text").put("content", t.content());
        }
        if (event instanceof AgentEvent.ToolCallEvent tc) {
            return Json.object()
                    .put("type", "tool_call")
                    .put("id", tc.id())
                    .put("toolName", tc.toolName())
                    .set("input", tc.input() == null ? Json.object() : tc.input());
        }
        if (event instanceof AgentEvent.ToolResultEvent tr) {
            return Json.object()
                    .put("type", "tool_result")
                    .put("id", tr.id())
                    .put("toolName", tr.toolName())
                    .put("isError", tr.isError())
                    .set("output", tr.output() == null ? Json.object() : tr.output());
        }
        if (event instanceof AgentEvent.ErrorEvent e) {
            return Json.object().put("type", "error").put("message", e.message());
        }
        if (event instanceof AgentEvent.DoneEvent) {
            return Json.object().put("type", "done");
        }
        if (event instanceof AgentEvent.ConfirmationRequestEvent cr) {
            com.fasterxml.jackson.databind.node.ObjectNode node = Json.object();
            node.put("type", "confirmation_request");
            node.put("confirmId", cr.confirmId());
            node.put("toolName", cr.toolName());
            node.put("message", cr.message());
            node.set("tags", Json.toNode(cr.tags() == null ? java.util.List.of() : cr.tags()));
            node.set("input", (cr.input() == null ? Json.object() : cr.input()));
            return node;
        }
        if (event instanceof AgentEvent.AskUserEvent au) {
            com.fasterxml.jackson.databind.node.ObjectNode node = Json.object()
                    .put("type", "ask_user")
                    .put("askId", au.askId())
                    .put("question", au.question())
                    .put("hint", au.hint() == null ? "" : au.hint());
            node.set("options", Json.toNode(au.options() == null ? java.util.List.of() : au.options()));
            return node;
        }
        return Json.object().put("type", "unknown");
    }
}
