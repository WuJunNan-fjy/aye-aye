package com.aye.server.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.util.Json;
import com.aye.server.storage.ModelProviderStore;
import com.aye.server.storage.ModelProviderStore.DefaultRef;
import com.aye.server.storage.ModelProviderStore.Provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 官方服务端搜索后端（流派 A：LLM Provider 原生 WebSearch）。
 *
 * <p>翻译自 deepseek-harness 的 {@code web-search-deepseek} provider：
 * 调用 DeepSeek 的 Anthropic 兼容端点 {@code /anthropic/v1/messages}，声明
 * {@code web_search_20250305} 服务端工具，由模型自己决定搜索词，返回结构化
 * {@code web_search_tool_result} 块。复用 DeepSeek 模型本身的 API Key，无需额外申请。
 *
 * <p>注意：端点固定为官方 {@code api.deepseek.com}（与 dsh 一致，只共享 key），
 * 不跟随用户在模型设置里配的 chat-completions baseUrl。
 */
public final class DeepSeekNativeWebSearchBackend implements WebSearchBackend {

    /** 官方 Anthropic 兼容端点（追加 {@code /messages}） */
    private static final String ANTHROPIC_BASE = "https://api.deepseek.com/anthropic/v1";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_USES = 3;

    private final ModelProviderStore modelStore;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DeepSeekNativeWebSearchBackend(ModelProviderStore modelStore) {
        this.modelStore = modelStore;
    }

    @Override
    public String name() { return "DEEPSEEK"; }

    @Override
    public List<SearchResult> search(String query, int maxResults, String apiKey) throws Exception {
        Provider provider = modelStore.get("deepseek");
        if (provider == null) {
            throw new IllegalStateException("未找到 DeepSeek 模型配置，无法使用服务端搜索");
        }
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : provider.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置 DeepSeek API Key，请在「设置 → 模型」里填入");
        }
        String model = pickModel(provider);

        ObjectNode body = Json.object();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        ObjectNode userMsg = Json.object().put("role", "user")
                .set("content", Json.array().add(Json.object()
                        .put("type", "text")
                        .put("text", "Perform a web search for the query: " + query)));
        body.set("messages", Json.array().add(userMsg));
        body.set("tools", Json.array().add(Json.object()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", MAX_USES)));

        HttpRequest req = HttpRequest.newBuilder(URI.create(ANTHROPIC_BASE + "/messages"))
                .timeout(Duration.ofSeconds(45))
                .header("x-api-key", key)
                .header("authorization", "Bearer " + key)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .header("user-agent", "aye/0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("DeepSeek 搜索失败（HTTP " + resp.statusCode() + "）："
                    + truncate(resp.body(), 300));
        }
        return mapResponse(Json.parse(resp.body()));
    }

    /** 优先用当前默认模型（若是 DeepSeek），否则取厂商目录第一个模型。 */
    private String pickModel(Provider provider) {
        DefaultRef d = modelStore.getDefaultRef();
        if (d != null && "deepseek".equals(d.providerId())) return d.modelId();
        return provider.models().isEmpty() ? "deepseek-v4-flash" : provider.models().get(0).modelId();
    }

    /**
     * 解析 Messages 响应：从 {@code web_search_tool_result} 块取 url/title/page_age，
     * 从 text 块的 {@code citations} 取 url → cited_text 作为摘要（与 dsh 相同）。
     */
    private static List<SearchResult> mapResponse(JsonNode root) {
        JsonNode content = root.path("content");
        List<SearchResult> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) continue;
            for (JsonNode cite : block.path("citations")) {
                String url = cite.path("url").asText("");
                String cited = cite.path("cited_text").asText("");
                if (url.isBlank() || cited.isBlank() || seen.contains(url)) continue;
                seen.add(url);
                out.add(new SearchResult(cite.path("title").asText(""), url, cited));
            }
        }
        if (out.isEmpty()) {
            // 无 citations 时退回 web_search_tool_result 块的裸链接
            for (JsonNode block : content) {
                if (!"web_search_tool_result".equals(block.path("type").asText())) continue;
                for (JsonNode item : block.path("content")) {
                    if (!"web_search_result".equals(item.path("type").asText())) continue;
                    String url = item.path("url").asText("");
                    if (url.isBlank() || seen.contains(url)) continue;
                    seen.add(url);
                    out.add(new SearchResult(item.path("title").asText(""), url, ""));
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
