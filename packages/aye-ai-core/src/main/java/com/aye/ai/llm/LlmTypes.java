package com.aye.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * LLM 协议层类型（OpenAI Chat Completions 兼容）。
 *
 * <p>对应 DeepWork 的 {@code llm/types.ts}。这些是「我们自己构造和消费」的类型，
 * 与 AI SDK 无关，完全自控。
 */
public final class LlmTypes {

    private LlmTypes() {}

    /** LLM 协议层消息：role/content/tool_calls/tool_call_id 四要素 */
    public record LlmMessage(String role, String content, List<LlmToolCall> toolCalls, String toolCallId) {
        public static LlmMessage system(String content) {
            return new LlmMessage("system", content, null, null);
        }

        public static LlmMessage user(String content) {
            return new LlmMessage("user", content, null, null);
        }

        public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
            return new LlmMessage("assistant", content, toolCalls, null);
        }

        public static LlmMessage tool(String toolCallId, String content) {
            return new LlmMessage("tool", content, null, toolCallId);
        }
    }

    /** 一次工具调用（id + function name + 序列化后的 arguments 字符串） */
    public record LlmToolCall(String id, String name, String arguments) {}

    /** 工具说明书（OpenAI 原生 tools 数组元素） */
    public record LlmToolDefinition(String type, Function function) {
        public record Function(String name, String description, JsonNode parameters) {}
    }

    /** Provider 配置（模型 + 端点 + 认证），由 app 层注入，AI 核心只消费 */
    public record LlmProviderConfig(
            String model,
            String baseURL,
            String apiKey,
            Double temperature,
            Boolean thinkingEnabled,
            AuthHeaderKind authHeaderKind,
            int maxRetries,
            Map<String, String> extraHeaders) {

        public static Builder builder(String model, String baseURL, String apiKey) {
            return new Builder(model, baseURL, apiKey);
        }

        public static final class Builder {
            private final String model;
            private final String baseURL;
            private final String apiKey;
            private Double temperature;
            private Boolean thinkingEnabled;
            private AuthHeaderKind authHeaderKind = AuthHeaderKind.AUTHORIZATION_BEARER;
            private int maxRetries = 2;
            private Map<String, String> extraHeaders = Map.of();

            private Builder(String model, String baseURL, String apiKey) {
                this.model = model;
                this.baseURL = baseURL;
                this.apiKey = apiKey;
            }

            public Builder temperature(Double v) { this.temperature = v; return this; }
            public Builder thinkingEnabled(Boolean v) { this.thinkingEnabled = v; return this; }
            public Builder authHeaderKind(AuthHeaderKind v) { this.authHeaderKind = v; return this; }
            public Builder maxRetries(int v) { this.maxRetries = v; return this; }
            public Builder extraHeaders(Map<String, String> v) { this.extraHeaders = v; return this; }

            public LlmProviderConfig build() {
                return new LlmProviderConfig(model, baseURL, apiKey, temperature,
                        thinkingEnabled, authHeaderKind, maxRetries, extraHeaders);
            }
        }
    }

    /** 认证头策略（不同厂商不同，DeepWork 兼容三种） */
    public enum AuthHeaderKind {
        AUTHORIZATION_BEARER,
        API_KEY,
        X_API_KEY
    }

    /** LLM 流式客户端内部事件（判别联合），被 AgentLoop 消费后转发为对外 AgentEvent */
    public sealed interface LlmEvent {

        record Thinking(String content) implements LlmEvent {}

        record Text(String content) implements LlmEvent {}

        record ToolCall(String id, String toolName, String arguments) implements LlmEvent {}

        record Done(String content, List<LlmToolCall> toolCalls, UsageStats usage, String stopReason)
                implements LlmEvent {}
    }

    /** token 用量统计 */
    public record UsageStats(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

    /** 流式调用选项 */
    public record LlmStreamOptions(
            java.util.function.BooleanSupplier aborted,
            Double temperature,
            Integer maxOutputTokens) {

        public static LlmStreamOptions of(java.util.function.BooleanSupplier aborted) {
            return new LlmStreamOptions(aborted, null, null);
        }
    }
}
