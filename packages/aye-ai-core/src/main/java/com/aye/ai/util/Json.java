package com.aye.ai.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 全局 JSON 工具（AI 核心内部唯一共享的 ObjectMapper）。
 *
 * <p>DeepWork 里 JSON 由 JS 原生 {@code JSON.parse / JSON.stringify} 承担，
 * 这里收敛到一处，避免到处 new ObjectMapper 造成配置漂移。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** 解析 JSON 字符串（非法 JSON 抛 JsonProcessingException） */
    public static JsonNode parse(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    /** 序列化任意对象为 JSON 字符串（失败退回 String.valueOf） */
    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 把对象转成 JsonNode（用于 AgentEvent 的 input/output 字段） */
    public static JsonNode toNode(Object value) {
        if (value instanceof JsonNode) {
            return (JsonNode) value;
        }
        return MAPPER.valueToTree(value);
    }
}
