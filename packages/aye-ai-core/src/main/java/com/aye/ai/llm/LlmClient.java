package com.aye.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.llm.LlmTypes.AuthHeaderKind;
import com.aye.ai.llm.LlmTypes.LlmEvent;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmProviderConfig;
import com.aye.ai.llm.LlmTypes.LlmStreamOptions;
import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.llm.LlmTypes.LlmToolDefinition;
import com.aye.ai.llm.LlmTypes.UsageStats;
import com.aye.ai.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 完全自研的 LLM 流式聊天客户端（OpenAI Chat Completions 兼容）。
 *
 * <p>对应 DeepWork 的 {@code llm/client.ts}。用 {@code java.net.http.HttpClient} 替代 fetch，
 * 用 {@code BodyHandlers.ofInputStream() + BufferedReader} 逐行解析 SSE（Java 自动处理
 * UTF-8 多字节边界，无需 TextDecoder stream 技巧）。
 *
 * <p>完整链路：组装请求体 → 指数退避重试 → 逐行解析 SSE → 兼容 reasoning_content/reasoning/thinking
 * 三种私有字段 → tool_calls delta 交给 ToolCallAccumulator 累积 → 流结束回调 done。
 */
public final class LlmClient {

    private LlmClient() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 单次非流式调用的完整结果 */
    public record LlmResponse(String content, List<LlmToolCall> toolCalls, UsageStats usage, String stopReason) {}

    // ---------------------------------------------------------------------------
    // 公共 API：流式
    // ---------------------------------------------------------------------------
    public static void chatStream(LlmProviderConfig cfg, List<LlmMessage> messages,
                                  List<LlmToolDefinition> tools, LlmStreamOptions options,
                                  Consumer<LlmEvent> sink) throws LlmException {
        ObjectNode request = buildRequest(cfg, messages, tools, true,
                options != null ? options.temperature() : null,
                options != null ? options.maxOutputTokens() : null);

        // 诊断日志：本次请求打到哪个端点/模型、发了多少工具定义（排查"模型不发起工具调用"）
        System.err.println("[AiDiagnose] 发送请求 baseURL=" + cfg.baseURL()
                + " model=" + cfg.model()
                + " tools=" + (tools == null ? 0 : tools.size())
                + " messages=" + messages.size()
                + " 最后顺序=" + messages.stream().map(LlmMessage::role).reduce((a, b) -> b).orElse("-"));

        HttpResponse<InputStream> response = doFetchWithRetry(cfg, request, options);

        StringBuilder fullText = new StringBuilder();
        boolean streamDone = false;
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        UsageStats usage = null;
        String stopReason = null;

        try (InputStream in = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (isAborted(options)) {
                    throw new LlmException("请求已取消");
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!trimmed.startsWith("data:")) continue;

                String dataText = trimmed.substring("data:".length());
                if (dataText.startsWith(" ")) dataText = dataText.substring(1);

                if ("[DONE]".equals(dataText)) {
                    streamDone = true;
                    if (stopReason == null) stopReason = "end_turn";
                    break;
                }

                JsonNode chunk;
                try {
                    chunk = Json.parse(dataText);
                } catch (Exception e) {
                    // 解析失败不打断，跳过继续（行截断边界的极端情况）
                    continue;
                }

                JsonNode usageNode = chunk.path("usage");
                if (!usageNode.isMissingNode()) {
                    usage = new UsageStats(
                            intOrNull(usageNode, "prompt_tokens"),
                            intOrNull(usageNode, "completion_tokens"),
                            intOrNull(usageNode, "total_tokens"));
                }

                JsonNode choice = chunk.path("choices").path(0);
                String fr = choice.path("finish_reason").asText(null);
                if ("length".equals(fr)) stopReason = "length";
                else if ("tool_calls".equals(fr)) stopReason = "tool_use";
                else if ("stop".equals(fr) && stopReason == null) stopReason = "end_turn";
                // 诊断日志：只在 finish_reason 出现时打印一次，定位模型是在 tool_calls 结束还是 stop 结束
                if (fr != null && !fr.isEmpty()) {
                    System.err.println("[AiDiagnose] finish_reason=" + fr + " (stopReason=" + stopReason + ")");
                }

                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode()) continue;

                // 思考内容：三种私有命名全兼容，取第一个非空
                String thinkingPiece = firstNonEmpty(
                        delta.path("reasoning_content").asText(null),
                        delta.path("reasoning").asText(null),
                        delta.path("thinking").asText(null));
                if (thinkingPiece != null && !thinkingPiece.isEmpty()) {
                    sink.accept(new LlmEvent.Thinking(thinkingPiece));
                }

                JsonNode contentNode = delta.path("content");
                if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
                    String text = contentNode.asText();
                    fullText.append(text);
                    sink.accept(new LlmEvent.Text(text));
                }

                JsonNode toolCallsDelta = delta.path("tool_calls");
                if (toolCallsDelta.isArray() && toolCallsDelta.size() > 0) {
                    List<Integer> completed = accumulator.addDelta(toolCallsDelta);
                    for (int index : completed) {
                        LlmToolCall snap = accumulator.snapshot(index);
                        if (snap == null) continue;
                        sink.accept(new LlmEvent.ToolCall(snap.id(), snap.name(), snap.arguments()));
                    }
                }
            }
        } catch (IOException e) {
            if (isAborted(options)) {
                throw new LlmException("请求已取消");
            }
            throw new LlmException("LLM 流式读取失败：" + e.getMessage(), e);
        }

        if (!streamDone) {
            // TCP 流关闭兜底：没收到 [DONE] 也按已结束处理
            stopReason = stopReason == null ? "end_turn" : stopReason;
        }

        List<LlmToolCall> toolCalls = accumulator.buildAll();
        // 流结束兜底：补发参数从未完整（如模型发空参）的调用，保证 UI/历史记录结构完整，
        // 且参数与 buildAll()（执行用）一致，不再在流中提前发出残缺快照。
        for (LlmToolCall trailing : accumulator.emitTrailing()) {
            sink.accept(new LlmEvent.ToolCall(trailing.id(), trailing.name(), trailing.arguments()));
        }
        // 诊断日志：本轮最终累积到的工具调用（空则说明模型没输出任何 tool_calls delta）
        System.err.println("[AiDiagnose] 本轮汇总 toolCalls=" + toolCalls.size()
                + " stopReason=" + stopReason);
        if (!toolCalls.isEmpty()) {
            for (LlmToolCall tc : toolCalls) {
                System.err.println("[AiDiagnose]   -> tool: " + tc.name() + " id=" + tc.id());
            }
        }
        sink.accept(new LlmEvent.Done(fullText.toString(), toolCalls, usage, stopReason));
    }

    // ---------------------------------------------------------------------------
    // 公共 API：非流式（内部仍走 stream=true）
    // ---------------------------------------------------------------------------
    public static LlmResponse chatNonStream(LlmProviderConfig cfg, List<LlmMessage> messages,
                                            List<LlmToolDefinition> tools, LlmStreamOptions options)
            throws LlmException {
        String[] content = {""};
        @SuppressWarnings("unchecked")
        List<LlmToolCall>[] toolCalls = new List[]{List.of()};
        UsageStats[] usage = {null};
        String[] stopReason = {null};

        chatStream(cfg, messages, tools, options, ev -> {
            if (ev instanceof LlmEvent.Text t) {
                content[0] += t.content();
            } else if (ev instanceof LlmEvent.Done d) {
                content[0] = d.content();
                toolCalls[0] = d.toolCalls();
                usage[0] = d.usage();
                stopReason[0] = d.stopReason();
            }
        });

        return new LlmResponse(content[0], toolCalls[0], usage[0], stopReason[0]);
    }

    // ---------------------------------------------------------------------------
    // 内部：组装请求体
    // ---------------------------------------------------------------------------
    private static ObjectNode buildRequest(LlmProviderConfig cfg, List<LlmMessage> messages,
                                           List<LlmToolDefinition> tools, boolean stream,
                                           Double temperature, Integer maxOutputTokens) {
        ObjectNode req = Json.object();
        req.put("model", cfg.model());
        req.put("stream", stream);

        ArrayNode msgs = req.putArray("messages");
        for (LlmMessage m : messages) {
            msgs.add(serializeMessage(m));
        }

        if (maxOutputTokens != null) req.put("max_tokens", maxOutputTokens);

        if (temperature != null) {
            req.put("temperature", temperature);
        } else if (cfg.temperature() != null) {
            req.put("temperature", cfg.temperature());
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode arr = req.putArray("tools");
            for (LlmToolDefinition t : tools) {
                ObjectNode o = arr.addObject();
                o.put("type", t.type());
                ObjectNode f = o.putObject("function");
                f.put("name", t.function().name());
                f.put("description", t.function().description());
                f.set("parameters", t.function().parameters());
            }
            // 显式声明工具选择策略为 auto（OpenAI 默认即 auto，但部分兼容端点需要该字段
            // presence 才会进入 function-calling 模式，否则会忽略 tools 仅返回纯文本）
            req.put("tool_choice", "auto");
        }

        if (Boolean.TRUE.equals(cfg.thinkingEnabled())) {
            ObjectNode thinking = req.putObject("thinking");
            thinking.put("type", "enabled");
        }
        return req;
    }

    private static ObjectNode serializeMessage(LlmMessage m) {
        ObjectNode o = Json.object();
        o.put("role", m.role());
        switch (m.role()) {
            case "tool":
                o.put("content", m.content());
                o.put("tool_call_id", m.toolCallId());
                break;
            case "assistant":
                if (m.content() != null) o.put("content", m.content());
                else o.putNull("content");
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    ArrayNode arr = o.putArray("tool_calls");
                    for (LlmToolCall tc : m.toolCalls()) {
                        ObjectNode t = arr.addObject();
                        t.put("id", tc.id());
                        t.put("type", "function");
                        ObjectNode f = t.putObject("function");
                        f.put("name", tc.name());
                        String args = (tc.arguments() == null || tc.arguments().trim().isEmpty())
                                ? "{}" : tc.arguments();
                        f.put("arguments", args);
                    }
                }
                break;
            default: // system / user
                o.put("content", m.content());
        }
        return o;
    }

    // ---------------------------------------------------------------------------
    // 内部：指数退避 + 重试
    // ---------------------------------------------------------------------------
    private static HttpResponse<InputStream> doFetchWithRetry(LlmProviderConfig cfg, ObjectNode requestBody,
                                                              LlmStreamOptions options) throws LlmException {
        String url = normalizeBaseURL(cfg.baseURL()) + "/chat/completions";
        Map<String, String> headers = buildRequestHeaders(cfg);
        byte[] body = Json.stringify(requestBody).getBytes(StandardCharsets.UTF_8);
        int maxRetries = cfg.maxRetries();

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (isAborted(options)) {
                throw new LlmException("请求已取消");
            }

            // 本次重试前的等待时长：默认本地指数退避（含 ±25% 抖动），可被服务端 Retry-After 覆盖
            long waitMs = jitterBackoff(attempt);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

            try {
                HttpResponse<InputStream> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return resp;
                }
                String bodyText = readBodyText(resp.body());
                if (!isRetryableStatus(status) || attempt == maxRetries) {
                    throw makeHttpError(status, bodyText);
                }
                // 服务端显式要求不要重试 → 立即放弃，避免无效等待
                if ("false".equalsIgnoreCase(resp.headers().firstValue("x-should-retry").orElse(""))) {
                    throw makeHttpError(status, bodyText);
                }
                // 尊重服务端 Retry-After 建议（取 max，既不比服务端急，也不因请求报 429 空等过久）
                long serverWait = parseRetryAfterMs(resp.headers().firstValue("Retry-After").orElse(null));
                if (serverWait > waitMs) waitMs = serverWait;
                lastError = new LlmException("HTTP " + status + "（重试 " + (attempt + 1) + "/" + maxRetries + "）");
            } catch (IOException | InterruptedException e) {
                lastError = e;
                if (attempt == maxRetries) {
                    throw new LlmException("LLM 请求失败：" + e.getMessage(), e);
                }
            }

            sleep(waitMs, options);
        }
        throw new LlmException("LLM 请求失败", lastError);
    }

    /** 本地指数退避 + ±25% 随机抖动（防惊群）。基数 500ms，每次 x2。 */
    private static long jitterBackoff(int attempt) {
        long base = 500L << attempt;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return (long) (base * (0.75 + 0.5 * rnd.nextDouble()));
    }

    /** 解析 HTTP {@code Retry-After} 头（支持秒数或 HTTP-date），解析失败返回 0。 */
    private static long parseRetryAfterMs(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) return 0L;
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException ignored) {
            // HTTP-date 形态（如 "Wed, 21 Oct 2015 07:28:00 GMT"）暂不解析，回退本地退避
            return 0L;
        }
    }

    private static String readBodyText(InputStream in) {
        try (InputStream is = in;
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static LlmException makeHttpError(int status, String bodyText) {
        String message = bodyText;
        try {
            JsonNode json = Json.parse(bodyText);
            JsonNode error = json.path("error");
            if (error.isObject() && error.path("message").isTextual()) {
                message = error.path("message").asText();
            } else if (error.isTextual()) {
                message = error.asText();
            } else if (message.length() > 500) {
                message = message.substring(0, 500) + "...";
            }
        } catch (Exception ignored) {
            if (message.length() > 500) message = message.substring(0, 500) + "...";
        }
        String suffix = message == null || message.isEmpty() ? "" : "：" + message;
        return new LlmException("LLM 请求失败：HTTP " + status + suffix);
    }

    private static Map<String, String> buildRequestHeaders(LlmProviderConfig cfg) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        AuthHeaderKind kind = cfg.authHeaderKind() != null ? cfg.authHeaderKind() : AuthHeaderKind.AUTHORIZATION_BEARER;
        switch (kind) {
            case API_KEY:
                headers.put("api-key", cfg.apiKey());
                break;
            case X_API_KEY:
                headers.put("x-api-key", cfg.apiKey());
                break;
            case AUTHORIZATION_BEARER:
            default:
                headers.put("Authorization", "Bearer " + cfg.apiKey());
                break;
        }
        if (cfg.extraHeaders() != null) {
            headers.putAll(cfg.extraHeaders());
        }
        return headers;
    }

    private static String normalizeBaseURL(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isRetryableStatus(int status) {
        if (status == 429) return true;
        return status >= 500 && status <= 599;
    }

    private static void sleep(long ms, LlmStreamOptions options) {
        long remaining = ms;
        while (remaining > 0) {
            if (isAborted(options)) return;
            long slice = Math.min(remaining, 100);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= slice;
        }
    }

    private static boolean isAborted(LlmStreamOptions options) {
        return options != null && options.aborted() != null && options.aborted().getAsBoolean();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }
}
