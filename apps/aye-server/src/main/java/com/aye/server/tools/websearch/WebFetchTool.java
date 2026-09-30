package com.aye.server.tools.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * web_fetch 工具 —— 抓取 URL 并提取正文为 Markdown（对标 Claude Code WebFetchTool）。
 *
 * <p>限制：仅 http/https；响应体最多读 512KB；输出 Markdown 最多
 * {@code max_length} 字符（默认 100K，对标 Claude Code 的 MAX_MARKDOWN_LENGTH 经验值）。
 * 不执行 JS，动态渲染页面前端请用浏览器。
 */
public final class WebFetchTool implements Tool {

    /** 响应体读取上限（字节） */
    private static final long MAX_RESPONSE_BYTES = 512L * 1024;
    /** 默认输出字符上限 */
    private static final int DEFAULT_MAX_LENGTH = 100_000;
    private static final int MAX_LENGTH_CAP = 200_000;
    /** 手动跟随重定向的最大跳数（每一跳都重新做 SSRF 校验） */
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient http = HttpClient.newBuilder()
            // 不自动跟随：必须由代码逐跳校验目标地址，防止外网 302 跳内网绕过 SSRF 防护
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "web_fetch"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "抓取一个网页 URL 并把正文提取成 Markdown 返回（只读）。"
                + "用于查看 web_search 搜到的链接全文、阅读官方文档/报错页面等。"
                + "默认最多返回 100K 字符，仅支持 http/https。"
                + "出于安全限制，禁止访问回环、内网、链路本地及云主机元数据地址（重定向跳转也会逐跳校验）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("url", JsonSchema.string()
                        .description("要抓取的完整 URL（http/https）")
                        .minLength(1)
                        .maxLength(2000))
                .property("max_length", JsonSchema.integer()
                        .description("返回正文的最大字符数，默认 100000，上限 200000")
                        .minimum(1000)
                        .maximum(MAX_LENGTH_CAP)
                        .defaultVal(DEFAULT_MAX_LENGTH))
                .required("url")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "web_fetch — 抓取网页正文为 Markdown（配 web_search 使用，只读）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        String url = input.path("url").asText("").trim();
        int maxLength = Math.min(input.path("max_length").asInt(DEFAULT_MAX_LENGTH), MAX_LENGTH_CAP);

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "[web_fetch] 仅支持 http/https 链接：" + url;
        }

        try {
            URI uri = URI.create(url);
            HttpResponse<byte[]> resp = null;
            int hops = 0;
            while (true) {
                // 每一跳都校验：scheme + 主机解析地址（拒绝回环/私网/链路本地/云元数据）
                SsrfGuard.assertUriAllowed(uri);
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(45))
                        .header("User-Agent", "Mozilla/5.0 (compatible; AyeFetch/1.0)")
                        .header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.8")
                        .GET()
                        .build();
                resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    String location = resp.headers().firstValue("Location").orElse("");
                    if (location.isBlank() || ++hops > MAX_REDIRECTS) {
                        return "[web_fetch] 抓取失败（HTTP " + sc + "，重定向"
                                + (location.isBlank() ? "缺少 Location" : "超过 " + MAX_REDIRECTS + " 跳")
                                + "）：" + url;
                    }
                    // 相对地址按当前 URI 解析；下一轮循环重新做完整 SSRF 校验
                    uri = uri.resolve(location);
                    continue;
                }
                break;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return "[web_fetch] 抓取失败（HTTP " + resp.statusCode() + "）：" + url;
            }

            byte[] body = resp.body();
            boolean truncated = body.length > MAX_RESPONSE_BYTES;
            byte[] slice = truncated ? java.util.Arrays.copyOf(body, (int) MAX_RESPONSE_BYTES) : body;
            String text = decode(slice);

            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            String md = contentType.toLowerCase().contains("html") ? HtmlToMarkdown.convert(text, url) : text.trim();

            if (md.isBlank()) {
                return "[web_fetch] 页面未提取到正文（可能是 JS 动态渲染）：" + url
                        + "\n建议改用浏览器访问该页面。";
            }
            if (md.length() > maxLength) md = md.substring(0, maxLength) + "\n…（已按 max_length 截断）";
            if (truncated) md += "\n…（响应体超过 512KB，已截断）";
            return md;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "[web_fetch] 请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (IllegalArgumentException e) {
            return "[web_fetch] 无效的 URL：" + e.getMessage();
        }
    }

    /** 优先按 UTF-8，回退平台默认 */
    private static String decode(byte[] bytes) {
        try {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(bytes, java.nio.charset.Charset.defaultCharset());
        }
    }

    // ==========================================================================
    // 轻量 HTML → Markdown 提取（够用即可：去脚本/样式/导航，保留标题/链接/正文）
    // ==========================================================================
    static final class HtmlToMarkdown {

        private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>");
        private static final Pattern STYLE = Pattern.compile("(?is)<style[^>]*>.*?</style>");
        private static final Pattern NOSCRIPT = Pattern.compile("(?is)<noscript[^>]*>.*?</noscript>");
        private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
        private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
        private static final Pattern LINK = Pattern.compile("(?is)<a\\s+[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>");
        private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
        private static final Pattern PRE = Pattern.compile("(?is)<pre[^>]*>(.*?)</pre>");
        private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
        private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
        private static final Pattern BLOCK = Pattern.compile("(?i)</(p|div|li|tr|table|ul|ol|section|article|h1|h2|h3|h4|h5|h6)>");

        private HtmlToMarkdown() {}

        static String convert(String html, String baseUrl) {
            String s = html;
            s = SCRIPT.matcher(s).replaceAll(" ");
            s = STYLE.matcher(s).replaceAll(" ");
            s = NOSCRIPT.matcher(s).replaceAll(" ");
            s = COMMENT.matcher(s).replaceAll(" ");

            Matcher titleM = TITLE.matcher(s);
            String title = "";
            if (titleM.find()) title = decodeEntities(titleM.group(1)).trim();

            // <pre> 块转代码块（防止后续通用链接/标题替换破坏代码）
            StringBuffer preBuf = new StringBuffer();
            Matcher preM = PRE.matcher(s);
            while (preM.find()) {
                preM.appendReplacement(preBuf, "\n```\n" + Matcher.quoteReplacement(decodeEntities(preM.group(1)).trim()) + "\n```\n");
            }
            preM.appendTail(preBuf);
            s = preBuf.toString();

            // 标题 → # 前缀
            StringBuffer hBuf = new StringBuffer();
            Matcher hM = HEADING.matcher(s);
            while (hM.find()) {
                String hash = "#".repeat(Integer.parseInt(hM.group(1)));
                String body = decodeEntities(hM.group(2)).trim();
                hM.appendReplacement(hBuf, Matcher.quoteReplacement("\n" + hash + " " + body + "\n"));
            }
            hM.appendTail(hBuf);
            s = hBuf.toString();

            // 链接 → [text](absUrl)
            StringBuffer aBuf = new StringBuffer();
            Matcher aM = LINK.matcher(s);
            while (aM.find()) {
                String href = aM.group(1).trim();
                String text = decodeEntities(aM.group(2)).replaceAll("(?s)<[^>]+>", "").trim();
                String abs = resolveUrl(baseUrl, href);
                aM.appendReplacement(aBuf, Matcher.quoteReplacement(
                        (text.isBlank() ? abs : text) + " (" + abs + ")"));
            }
            aM.appendTail(aBuf);
            s = aBuf.toString();

            s = BR.matcher(s).replaceAll("\n");
            s = BLOCK.matcher(s).replaceAll("\n");
            s = TAG.matcher(s).replaceAll(" ");
            s = decodeEntities(s);

            String out = title.isBlank() ? "" : "# " + title + "\n\n";
            out += collapseBlankLines(s);
            return out.trim();
        }

        private static String resolveUrl(String base, String href) {
            if (href.isBlank()) return href;
            try {
                URI baseUri = URI.create(base);
                URI u = baseUri.resolve(href);
                return u.toString();
            } catch (Exception e) {
                return href;
            }
        }

        private static String collapseBlankLines(String s) {
            String[] lines = s.split("\n");
            StringBuilder sb = new StringBuilder();
            boolean prevBlank = false;
            for (String ln : lines) {
                String t = ln.trim();
                if (t.isEmpty()) {
                    if (!prevBlank) sb.append('\n');
                    prevBlank = true;
                } else {
                    sb.append(t).append('\n');
                    prevBlank = false;
                }
            }
            return sb.toString();
        }

        /** 常见 HTML 实体 + 数字实体解码（够用即可） */
        static String decodeEntities(String s) {
            if (s == null) return "";
            String out = s
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&hellip;", "…")
                    .replace("&mdash;", "—")
                    .replace("&ndash;", "–")
                    .replace("&copy;", "©");
            Matcher m = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String rep = "";
                try {
                    int code = m.group(1).startsWith("x") || m.group(1).startsWith("X")
                            ? Integer.parseInt(m.group(1).substring(1), 16)
                            : Integer.parseInt(m.group(1));
                    rep = code > 0 && code < 0x10FFFF ? String.valueOf((char) code) : "";
                } catch (NumberFormatException ignored) {}
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }
}
