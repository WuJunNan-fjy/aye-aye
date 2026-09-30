package com.aye.server.tools.websearch;

import java.util.List;

/**
 * 联网搜索后端 SPI（对应竞品文档「流派 A：LLM Provider 原生 WebSearch」与
 * 「流派 B：第三方 Search API」的统一抽象）。
 *
 * <p>扩展点：未来接入 Provider 原生搜索（如 Anthropic web_search_20250305）时，
 * 新增一个实现类（如 {@code AnthropicNativeWebSearchBackend}）并在配置里把
 * provider 指过去即可，web_search 工具本身不需要改。
 * 流派 A 后端没有独立的搜索 key（用模型自身凭据），可忽略 {@code apiKey} 参数。
 */
public interface WebSearchBackend {

    /** 后端标识（与设置里 {@code webSearch.provider} 匹配，大小写不敏感） */
    String name();

    /** 执行搜索，返回去重后的结果列表（title/url/content 摘要） */
    List<SearchResult> search(String query, int maxResults, String apiKey) throws Exception;

    /** 搜索结果条目 */
    record SearchResult(String title, String url, String content) {}
}
