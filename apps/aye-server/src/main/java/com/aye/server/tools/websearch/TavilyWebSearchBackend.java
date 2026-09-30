package com.aye.server.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily Search API 后端（流派 B：第三方 Search API + 自管 Pipeline）。
 *
 * <p>选 Tavily 的原因：结果自带清洗过的 markdown 摘要（content 字段），
 * 免去自家做正文提取；有免费额度；OpenAI 兼容生态常用。
 * API：{@code POST https://api.tavily.com/search}，body 带 {@code api_key}。
 */
public final class TavilyWebSearchBackend implements WebSearchBackend {

    private static final String ENDPOINT = "https://api.tavily.com/search";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "TAVILY"; }

    @Override
    public List<SearchResult> search(String query, int maxResults, String apiKey) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 Tavily API Key");
        }

        JsonNode body = Json.object()
                .put("api_key", apiKey)
                .put("query", query)
                .put("max_results", maxResults)
                .put("search_depth", "basic");
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Tavily 搜索失败（HTTP " + resp.statusCode() + "）："
                    + truncate(resp.body(), 300));
        }

        JsonNode root = Json.parse(resp.body());
        List<SearchResult> out = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode r : results) {
                String title = r.path("title").asText("");
                String url = r.path("url").asText("");
                String content = r.path("content").asText("");
                if (title.isBlank() && url.isBlank()) continue;
                out.add(new SearchResult(title, url, content));
            }
        }
        if (out.isEmpty()) {
            String err = root.path("error").asText("");
            throw new IllegalStateException("Tavily 无搜索结果"
                    + (err.isBlank() ? "" : "（" + err + "）"));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
