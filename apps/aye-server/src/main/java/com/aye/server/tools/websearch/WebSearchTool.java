package com.aye.server.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.storage.ModelProviderStore;
import com.aye.server.storage.ModelProviderStore.DefaultRef;
import com.aye.server.storage.SettingsStore;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * web_search 工具 —— 联网搜索，按当前模型自动选择后端（开箱即用，无需额外配置）。
 *
 * <p>路由规则（设置里 {@code webSearch.provider}，默认 {@code AUTO}）：
 * <ul>
 *   <li>{@code AUTO}：默认模型是 DeepSeek → 用 DeepSeek 官方服务端搜索（复用模型 Key）；
 *       其他模型 → Exa 免费 MCP（零 Key）。</li>
 *   <li>{@code DEEPSEEK} / {@code EXA} / {@code TAVILY}：显式指定后端（Tavily 需自行填 Key）。</li>
 * </ul>
 */
public final class WebSearchTool implements Tool {

    /** 单条结果 content 摘要的展示上限（字符） */
    private static final int CONTENT_SNIPPET = 300;

    private final SettingsStore settings;
    private final ModelProviderStore modelStore;
    private final List<WebSearchBackend> backends;

    public WebSearchTool(SettingsStore settings, ModelProviderStore modelStore, List<WebSearchBackend> backends) {
        this.settings = settings;
        this.modelStore = modelStore;
        this.backends = backends;
    }

    @Override
    public String name() { return "web_search"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "联网搜索网页内容。DeepSeek 模型自动走官方服务端搜索（复用模型 Key，无需配置），"
                + "其他模型走 Exa 免费搜索。用于查询报错解决方案、官方文档、依赖版本、最佳实践等。"
                + "返回标题/链接/内容摘要，需要看全文时再用 web_fetch 抓取页面。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("query", JsonSchema.string()
                        .description("搜索关键词，尽量具体（如：nginx 413 Request Entity Too Large 解决）")
                        .minLength(1)
                        .maxLength(300))
                .property("max_results", JsonSchema.integer()
                        .description("返回结果条数，默认 5，上限 10")
                        .minimum(1)
                        .maximum(10)
                        .defaultVal(5))
                .required("query")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "web_search — 联网搜索（查报错/文档/版本用，DeepSeek 免配置，其他模型走 Exa 免费搜索）。";
    }

    @Override
    public String promptGuidelines() {
        return """
                ### web_search
                - 遇到服务器报错、配置问题、想查官方文档或依赖版本时，先 web_search 再下结论。
                - 查询词用具体错误文本或关键词组合（如 "nginx 413 Request Entity Too Large"）。
                - 只凭摘要不足以确定时，用 web_fetch 抓取结果里的链接看全文。""";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        String query = input.path("query").asText("");
        int maxResults = input.path("max_results").asInt(5);

        Map<String, Object> cfg = webSearchConfig();
        String provider = resolveProvider(String.valueOf(cfg.getOrDefault("provider", "AUTO")));
        String apiKey = String.valueOf(cfg.getOrDefault("apiKey", ""));

        WebSearchBackend backend = findBackend(provider);
        if (backend == null) {
            return "[web_search] 不支持的搜索服务：" + provider + "。当前支持："
                    + backends.stream().map(WebSearchBackend::name).toList();
        }
        try {
            List<WebSearchBackend.SearchResult> results = backend.search(query, maxResults, apiKey);
            if (results.isEmpty()) {
                return "[web_search] 没有找到相关结果，换个关键词试试。";
            }
            return formatResults(results);
        } catch (Exception e) {
            return "[web_search] 搜索失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** AUTO 路由：默认模型是 DeepSeek → DEEPSEEK，否则 → EXA（免费兜底）。 */
    private String resolveProvider(String configured) {
        String p = configured.toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(p)) return p;
        DefaultRef d = modelStore.getDefaultRef();
        if (d != null && "deepseek".equals(d.providerId())) return "DEEPSEEK";
        return "EXA";
    }

    private Map<String, Object> webSearchConfig() {
        Object raw = settings.getAll().get("webSearch");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of("provider", "AUTO", "apiKey", "");
    }

    private WebSearchBackend findBackend(String provider) {
        for (WebSearchBackend b : backends) {
            if (b.name().equalsIgnoreCase(provider)) return b;
        }
        return null;
    }

    private static String formatResults(List<WebSearchBackend.SearchResult> results) {
        StringBuilder sb = new StringBuilder("[web_search] 找到 ").append(results.size()).append(" 条结果：\n");
        for (int i = 0; i < results.size(); i++) {
            WebSearchBackend.SearchResult r = results.get(i);
            sb.append('\n').append(i + 1).append(". ").append(r.title().isBlank() ? "(无标题)" : r.title())
                    .append('\n').append("   链接：").append(r.url());
            if (!r.content().isBlank()) {
                String c = r.content();
                sb.append("\n   摘要：").append(c.length() <= CONTENT_SNIPPET ? c : c.substring(0, CONTENT_SNIPPET) + "…");
            }
        }
        return sb.toString();
    }
}
