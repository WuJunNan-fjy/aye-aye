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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exa 免费 MCP 后端（流派 B：第三方 Search API，零 Key）。
 *
 * <p>翻译自 OpenCode 的 {@code mcp-websearch}：POST JSON-RPC {@code tools/call} 到
 * Exa 的公共 MCP 端点 {@code https://mcp.exa.ai/mcp}，无需 API Key（配了 {@code EXA_API_KEY}
 * 也只是提升额度）。响应为 SSE 流，从 {@code data:} 行解析出结果文本块。
 */
public final class ExaMcpWebSearchBackend implements WebSearchBackend {

    private static final String EXA_URL = "https://mcp.exa.ai/mcp";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Pattern TITLE = Pattern.compile("Title:\\s*(.+)");
    private static final Pattern URL = Pattern.compile("URL:\\s*(\\S+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "EXA"; }

    @Override
    public List<SearchResult> search(String query, int maxResults, String apiKey) throws Exception {
        JsonNode body = Json.object()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .set("params", Json.object()
                        .put("name", "web_search_exa")
                        .set("arguments", Json.object()
                                .put("query", query)
                                .put("type", "auto")
                                .put("numResults", Math.min(maxResults, 10))
                                .put("livecrawl", "fallback")));

        HttpRequest req = HttpRequest.newBuilder(URI.create(EXA_URL))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .header("user-agent", "aye/0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Exa 搜索失败（HTTP " + resp.statusCode() + "）："
                    + truncate(resp.body(), 300));
        }
        String text = extractMcpText(resp.body());
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Exa 无搜索结果");
        }
        return parseResultText(text);
    }

    /** 从 JSON 或 SSE（data: 行）响应里取出工具返回的 text。 */
    private static String extractMcpText(String body) {
        String data = null;
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            data = trimmed;
        } else {
            for (String line : body.split("\n")) {
                if (line.startsWith("data: ")) {
                    data = line.substring(6);
                    break;
                }
            }
        }
        if (data == null) return null;
        try {
            JsonNode content = Json.parse(data).path("result").path("content");
            if (content.isArray()) {
                for (JsonNode c : content) {
                    String t = c.path("text").asText("");
                    if (!t.isBlank()) return t;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /** 解析 Exa 结果文本（多个 "Title: …" 块），提取 title/url/highlights 摘要。 */
    private static List<SearchResult> parseResultText(String text) {
        List<SearchResult> out = new ArrayList<>();
        String[] blocks = text.split("(?=Title: )");
        for (String block : blocks) {
            Matcher mTitle = TITLE.matcher(block);
            Matcher mUrl = URL.matcher(block);
            if (!mUrl.find()) continue;
            String title = mTitle.find() ? mTitle.group(1).trim() : "";
            String url = mUrl.group(1);
            // Highlights 之后的内容作为摘要，限制长度防刷屏
            int hIdx = block.indexOf("Highlights:");
            String content = hIdx >= 0 ? block.substring(hIdx + "Highlights:".length()).trim() : "";
            if (content.length() > 500) content = content.substring(0, 500) + "…";
            out.add(new SearchResult(title, url, content));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
