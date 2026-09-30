package com.aye.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.util.Json;

/**
 * 手写 JSON Schema 建造器（对应 DeepWork 里 Zod schema 在工具参数上的角色）。
 *
 * <p>DeepWork 用 {@code z.object(...).describe(...)} 声明工具参数，再由 {@code zodToJsonSchema}
 * 转换成 OpenAI 工具的 JSON Schema。这里直接构造 JSON Schema，无需 zod 依赖。
 * 覆盖工具参数 99% 的场景：object / string / integer / number / boolean / array / enum。
 */
public final class JsonSchema {

    private final ObjectNode node;

    private JsonSchema(ObjectNode node) {
        this.node = node;
    }

    public static JsonSchema object() {
        ObjectNode n = Json.object();
        n.put("type", "object");
        n.putObject("properties");
        n.putArray("required");
        n.put("additionalProperties", false);
        return new JsonSchema(n);
    }

    public static JsonSchema string() { return new JsonSchema(Json.object().put("type", "string")); }
    public static JsonSchema integer() { return new JsonSchema(Json.object().put("type", "integer")); }
    public static JsonSchema number() { return new JsonSchema(Json.object().put("type", "number")); }
    public static JsonSchema bool() { return new JsonSchema(Json.object().put("type", "boolean")); }

    public static JsonSchema array(JsonSchema items) {
        ObjectNode n = Json.object();
        n.put("type", "array");
        n.set("items", items.node);
        return new JsonSchema(n);
    }

    /** 追加描述（对应 z.describe） */
    public JsonSchema description(String d) {
        node.put("description", d);
        return this;
    }

    /** object 专用：声明一个属性（optional 默认不加入 required） */
    public JsonSchema property(String name, JsonSchema schema) {
        ((ObjectNode) node.get("properties")).set(name, schema.node);
        return this;
    }

    /** object 专用：标记必填字段 */
    public JsonSchema required(String... names) {
        ArrayNode arr = (ArrayNode) node.get("required");
        for (String n : names) arr.add(n);
        return this;
    }

    /** 默认值（对应 z.default） */
    public JsonSchema defaultVal(JsonNode v) {
        node.set("default", v);
        return this;
    }

    public JsonSchema defaultVal(String v) {
        node.put("default", v);
        return this;
    }

    /** 默认值（数值，对应 z.number().default，整数保整数、小数保小数） */
    public JsonSchema defaultVal(Number v) {
        if (v instanceof Integer || v instanceof Long) {
            node.put("default", v.longValue());
        } else {
            node.put("default", v.doubleValue());
        }
        return this;
    }

    /** 枚举值（对应 z.enum） */
    public JsonSchema enumValues(String... values) {
        ArrayNode arr = node.putArray("enum");
        for (String v : values) arr.add(v);
        return this;
    }

    /** 字符串最小长度（对应 z.string().min） */
    public JsonSchema minLength(int n) { node.put("minLength", n); return this; }

    /** 字符串最大长度（对应 z.string().max） */
    public JsonSchema maxLength(int n) { node.put("maxLength", n); return this; }

    /** 数值最小值（对应 z.number().min） */
    public JsonSchema minimum(Number n) { node.put("minimum", n.doubleValue()); return this; }

    /** 数值最大值（对应 z.number().max） */
    public JsonSchema maximum(Number n) { node.put("maximum", n.doubleValue()); return this; }

    /** 数组最小元素数（对应 z.array().min） */
    public JsonSchema minItems(int n) { node.put("minItems", n); return this; }

    /** 数组最大元素数（对应 z.array().max） */
    public JsonSchema maxItems(int n) { node.put("maxItems", n); return this; }

    public ObjectNode build() {
        return node;
    }
}
