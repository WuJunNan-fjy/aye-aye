package com.aye.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * tool_calls 流式碎片累积器。
 *
 * <p>对应 DeepWork 的 {@code llm/accumulator.ts}。SSE 流里一次工具调用的
 * id / name / arguments 会分片到达（arguments 尤其会拆成多个 delta），
 * 这里按 index 累积，并在某个调用 id+name 首次齐全时标记为「新就绪」。
 */
public final class ToolCallAccumulator {

    private final Map<Integer, Accum> items = new TreeMap<>();

    private static final class Accum {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        boolean emitted; // 是否已作为「参数完整」发射过 ToolCallEvent
    }

    /**
     * 消费一个 chunk 里的 {@code delta.tool_calls} 数组。
     *
     * <p>与旧逻辑（id+name 刚齐即视为就绪）不同，这里只在「id/name 已齐且累计参数
     * 能解析成合法 JSON」时才标记完成——即参数的最后一个分片到达、JSON 真正闭合的时刻，
     * 避免把参数尚未流完（空/残缺）的快照提前发出去污染 UI 与历史记录。
     *
     * @return 本轮参数完整、可发射的工具调用 index 列表（每个 index 只会返回一次）
     */
    public List<Integer> addDelta(JsonNode toolCalls) {
        List<Integer> completed = new ArrayList<>();
        if (toolCalls == null || !toolCalls.isArray()) {
            return completed;
        }
        for (JsonNode tc : toolCalls) {
            int index = tc.path("index").asInt(0);
            Accum a = items.computeIfAbsent(index, k -> new Accum());

            if (a.id == null && tc.hasNonNull("id")) {
                a.id = tc.get("id").asText();
            }
            JsonNode fn = tc.path("function");
            if (a.name == null && fn.hasNonNull("name")) {
                a.name = fn.get("name").asText();
            }
            if (fn.hasNonNull("arguments")) {
                a.arguments.append(fn.get("arguments").asText());
            }

            if (!a.emitted && a.id != null && a.name != null && isCompleteJson(a.arguments)) {
                a.emitted = true;
                completed.add(index);
            }
        }
        return completed;
    }

    /**
     * 参数是否已完整：累计的 JSON 文本能成功解析（首个分片让 JSON 闭合即视为完整）。
     */
    private static boolean isCompleteJson(StringBuilder sb) {
        String s = sb.toString();
        if (s == null || s.trim().isEmpty()) return false;
        try {
            return Json.parse(s) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 流结束兜底：补发「id/name 已齐但参数从未完整」的调用（例如模型发了空参）。
     *
     * <p>这些调用虽然参数缺失，但必须照实发射，保证 recorder / UI 的
     * {@code assistant(tool_call) + tool(result)} 结构成对、能够正确回放。
     * 参数取最终累计值，与 {@link #buildAll()}（真正的执行参数）保持一致。
     */
    public List<LlmToolCall> emitTrailing() {
        List<LlmToolCall> out = new ArrayList<>();
        for (Accum a : items.values()) {
            if (!a.emitted && a.id != null && a.name != null) {
                out.add(new LlmToolCall(a.id, a.name, a.arguments.toString()));
            }
        }
        return out;
    }

    /** 取某个 index 的当前累积快照（未就绪返回 null） */
    public LlmToolCall snapshot(int index) {
        Accum a = items.get(index);
        if (a == null || a.id == null || a.name == null) {
            return null;
        }
        return new LlmToolCall(a.id, a.name, a.arguments.toString());
    }

    /** 流结束时取所有已累积完成的工具调用 */
    public List<LlmToolCall> buildAll() {
        List<LlmToolCall> out = new ArrayList<>();
        for (Accum a : items.values()) {
            if (a.id != null && a.name != null) {
                out.add(new LlmToolCall(a.id, a.name, a.arguments.toString()));
            }
        }
        return out;
    }
}
