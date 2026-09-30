package com.aye.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 轻量 JSON Schema 校验器（对应 DeepWork 里 Zod 的 {@code safeParse}）。
 *
 * <p>只覆盖工具参数 schema 的常见结构：object（required/properties/additionalProperties）、
 * string / integer / number / boolean / array / enum / null。不追求通用，够工具参数用即可。
 * 返回错误文案列表（空 = 通过）。
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {}

    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validateInto(schema, value, "<root>", errors);
        return errors;
    }

    private static void validateInto(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null) return;
        if (schema.has("enum")) {
            boolean hit = false;
            for (JsonNode e : schema.get("enum")) {
                if (e.equals(value)) { hit = true; break; }
            }
            if (!hit) errors.add(path + ": 值必须是 " + schema.get("enum").toString());
        }

        String type = schema.path("type").asText(null);
        if (type != null) {
            if (!matchesType(type, value)) {
                errors.add(path + ": 期望类型 " + type + "，实际 " + value.getNodeType().name().toLowerCase());
                return;
            }
        }

        // 字符串长度约束
        if (value.isTextual()) {
            int len = value.asText().length();
            if (schema.has("minLength") && len < schema.get("minLength").asInt()) {
                errors.add(path + ": 长度 " + len + " 小于最小长度 " + schema.get("minLength").asInt());
            }
            if (schema.has("maxLength") && len > schema.get("maxLength").asInt()) {
                errors.add(path + ": 长度 " + len + " 大于最大长度 " + schema.get("maxLength").asInt());
            }
        }

        // 数值范围约束
        if (value.isNumber()) {
            double v = value.asDouble();
            if (schema.has("minimum") && v < schema.get("minimum").asDouble()) {
                errors.add(path + ": 值 " + v + " 小于最小值 " + schema.get("minimum").asDouble());
            }
            if (schema.has("maximum") && v > schema.get("maximum").asDouble()) {
                errors.add(path + ": 值 " + v + " 大于最大值 " + schema.get("maximum").asDouble());
            }
        }

        if ("object".equals(type) && value.isObject()) {
            ObjectNode obj = (ObjectNode) value;
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                Iterator<String> names = props.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode sub = props.get(name);
                    if (obj.has(name)) {
                        validateInto(sub, obj.get(name), path + "." + name, errors);
                    } else if (isRequired(schema, name) && !sub.has("default")) {
                        errors.add(path + "." + name + ": 缺少必填字段");
                    }
                }
            }
            if (schema.path("additionalProperties").asBoolean(false) == false && props != null) {
                Iterator<String> actual = obj.fieldNames();
                while (actual.hasNext()) {
                    String name = actual.next();
                    if (!props.has(name)) {
                        errors.add(path + "." + name + ": 未声明的字段");
                    }
                }
            }
        }

        if ("array".equals(type) && value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                int i = 0;
                for (JsonNode item : (ArrayNode) value) {
                    validateInto(items, item, path + "[" + i + "]", errors);
                    i++;
                }
            }
        }
    }

    private static boolean isRequired(JsonNode objectSchema, String name) {
        JsonNode req = objectSchema.get("required");
        if (req == null || !req.isArray()) return false;
        for (JsonNode n : req) {
            if (n.asText().equals(name)) return true;
        }
        return false;
    }

    private static boolean matchesType(String type, JsonNode value) {
        switch (type) {
            case "string": return value.isTextual();
            case "integer": return value.isIntegralNumber();
            case "number": return value.isNumber();
            case "boolean": return value.isBoolean();
            case "object": return value.isObject();
            case "array": return value.isArray();
            case "null": return value.isNull();
            default: return true;
        }
    }
}
