package com.aye.server.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * UIMessage parts 数组 ↔ JSON 编解码（独立于 Jackson 多态，手工映射 5 种 part）。
 *
 * <p>parts 的 wire 格式（每个 part 带 {@code type} 判别字段）：
 * <pre>
 *   {"type":"text","text":"..."}
 *   {"type":"reasoning","text":"..."}
 *   {"type":"tool_call","id":"..","toolName":"..","input":{...}}
 *   {"type":"tool_result","id":"..","output":{...},"isError":false}
 *   {"type":"context","name":"..","summary":"..."}
 *   {"type":"interaction_confirm","id":"..","toolName":"..","message":"..","tags":[..],"input":{..},"approved":true}
 *   {"type":"interaction_ask","id":"..","question":"..","hint":null,"options":[..],"answer":"..","skipped":false}
 * </pre>
 * {@code context} / {@code interaction_*} part 仅用于前端时间线展示，不送入 LLM。
 */
public final class MessageCodec {

    private MessageCodec() {}

    /** parts 数组 → JSON 字符串 */
    public static String serializeParts(List<UIPart> parts) {
        return Json.stringify(partsToArray(parts));
    }

    /** parts 数组 → ArrayNode（带 type 判别字段） */
    public static ArrayNode partsToArray(List<UIPart> parts) {
        ArrayNode arr = Json.array();
        for (UIPart p : parts) {
            arr.add(partToNode(p));
        }
        return arr;
    }

    /** 单条消息 → wire 格式 {@code {role, parts:[...]}} */
    public static ObjectNode messageToNode(UIMessage m) {
        ObjectNode o = Json.object();
        o.put("role", m.role());
        o.set("parts", partsToArray(m.parts()));
        return o;
    }

    /** 消息列表 → wire 数组 */
    public static ArrayNode messagesToArray(List<UIMessage> msgs) {
        ArrayNode arr = Json.array();
        for (UIMessage m : msgs) {
            arr.add(messageToNode(m));
        }
        return arr;
    }

    /** JSON 字符串 → parts 数组 */
    public static List<UIPart> parseParts(String json) {
        List<UIPart> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        JsonNode arr;
        try {
            arr = Json.parse(json);
        } catch (Exception e) {
            return out;
        }
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            UIPart p = nodeToPart(n);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static ObjectNode partToNode(UIPart p) {
        ObjectNode n = Json.object();
        if (p instanceof UIPart.TextPart t) {
            n.put("type", "text");
            n.put("text", t.text());
        } else if (p instanceof UIPart.ReasoningPart r) {
            n.put("type", "reasoning");
            n.put("text", r.text());
        } else if (p instanceof UIPart.ToolCallPart tc) {
            n.put("type", "tool_call");
            n.put("id", tc.id());
            n.put("toolName", tc.toolName());
            n.set("input", tc.input() == null ? Json.object() : tc.input());
        } else if (p instanceof UIPart.ToolResultPart tr) {
            n.put("type", "tool_result");
            n.put("id", tr.id());
            n.set("output", tr.output() == null ? Json.object() : tr.output());
            n.put("isError", tr.isError());
        } else if (p instanceof UIPart.ContextPart cp) {
            n.put("type", "context");
            n.put("name", cp.name());
            n.put("summary", cp.summary() == null ? "" : cp.summary());
        } else if (p instanceof UIPart.InteractionConfirmPart ic) {
            n.put("type", "interaction_confirm");
            n.put("id", ic.confirmId());
            n.put("toolName", ic.toolName());
            n.put("message", ic.message() == null ? "" : ic.message());
            n.set("tags", toStringArray(ic.tags()));
            n.set("input", ic.input() == null ? Json.object() : ic.input());
            n.put("approved", ic.approved());
        } else if (p instanceof UIPart.InteractionAskPart ia) {
            n.put("type", "interaction_ask");
            n.put("id", ia.askId());
            n.put("question", ia.question() == null ? "" : ia.question());
            if (ia.hint() == null) n.putNull("hint");
            else n.put("hint", ia.hint());
            n.set("options", toStringArray(ia.options()));
            if (ia.answer() == null) n.putNull("answer");
            else n.put("answer", ia.answer());
            n.put("skipped", ia.skipped());
        }
        return n;
    }

    private static ArrayNode toStringArray(java.util.List<String> items) {
        ArrayNode a = Json.array();
        if (items != null) for (String s : items) a.add(s);
        return a;
    }

    private static java.util.List<String> fromStringArray(JsonNode n) {
        java.util.List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode i : n) out.add(i.asText(""));
        }
        return out;
    }

    private static String nullableText(JsonNode parent, String field) {
        JsonNode v = parent.get(field);
        return v == null || v.isNull() ? null : v.asText("");
    }

    private static UIPart nodeToPart(JsonNode n) {
        String type = n.path("type").asText("");
        return switch (type) {
            case "text" -> new UIPart.TextPart(n.path("text").asText(""));
            case "reasoning" -> new UIPart.ReasoningPart(n.path("text").asText(""));
            case "tool_call" -> new UIPart.ToolCallPart(
                    n.path("id").asText(""),
                    n.path("toolName").asText(""),
                    n.path("input").isMissingNode() ? Json.object() : n.path("input"));
            case "tool_result" -> new UIPart.ToolResultPart(
                    n.path("id").asText(""),
                    n.path("output").isMissingNode() ? Json.object() : n.path("output"),
                    n.path("isError").asBoolean(false));
            case "context" -> new UIPart.ContextPart(
                    n.path("name").asText(""),
                    n.path("summary").asText(""));
            case "interaction_confirm" -> new UIPart.InteractionConfirmPart(
                    n.path("id").asText(""),
                    n.path("toolName").asText(""),
                    n.path("message").asText(""),
                    fromStringArray(n.get("tags")),
                    n.path("input").isMissingNode() ? Json.object() : n.path("input"),
                    n.path("approved").asBoolean(false));
            case "interaction_ask" -> new UIPart.InteractionAskPart(
                    n.path("id").asText(""),
                    n.path("question").asText(""),
                    nullableText(n, "hint"),
                    fromStringArray(n.get("options")),
                    nullableText(n, "answer"),
                    n.path("skipped").asBoolean(false));
            default -> null;
        };
    }
}
