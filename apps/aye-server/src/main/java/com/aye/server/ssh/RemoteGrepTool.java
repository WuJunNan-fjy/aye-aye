package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshSessionManager.ExecResult;

import java.util.ArrayList;
import java.util.List;

/**
 * remote_grep 工具 —— 在远程服务器上按正则搜索文件内容（sftp 族的搜索能力补齐）。
 *
 * <p>通过 SSH 执行 {@code grep -rn} 实现，等价于本地 grep/ripgrep 的运维场景
 * （搜配置、搜日志关键字）。只读工具，权限引擎直接放行。
 */
public final class RemoteGrepTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int LINE_LIMIT = 500;   // 单行最长返回字符
    private static final int TOTAL_LIMIT = 200_000; // 总输出上限字符

    private final SshToolSupport support;
    private final SshSessionManager manager;

    public RemoteGrepTool(SshToolSupport support, SshSessionManager manager) {
        this.support = support;
        this.manager = manager;
    }

    @Override
    public String name() { return "remote_grep"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "在远程服务器指定目录下按正则搜索文件内容（等价远程 grep -rn）。"
                + "用于搜索日志关键字、配置文件项等。返回 文件路径:行号:内容 的匹配列表。"
                + "支持 include/exclude 文件名通配符过滤与 ignore_case 忽略大小写。"
                + "host_key 先用 list_hosts 查询。"
                + "按内容搜索时优先于 run_ssh 执行 grep（参数结构化、自动处理转义与结果截断、只读免确认）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("pattern", JsonSchema.string()
                        .description("要搜索的正则表达式（grep -E 扩展正则，如 nginx|apache）")
                        .minLength(1)
                        .maxLength(500))
                .property("path", JsonSchema.string()
                        .description("起始目录绝对路径（递归搜索，如 /etc/nginx）"))
                .property("include", JsonSchema.string()
                        .description("可选：只搜匹配此文件名通配符的文件，逗号分隔多个（如 *.conf,*.log）"))
                .property("exclude", JsonSchema.string()
                        .description("可选：排除匹配此文件名通配符的文件，逗号分隔多个（如 *.gz,*.min.js）"))
                .property("ignore_case", JsonSchema.bool()
                        .description("可选：true 时忽略大小写（grep -i），默认 false"))
                .property("max_results", JsonSchema.integer()
                        .description("最多返回的匹配行数，默认 50，上限 500")
                        .minimum(1)
                        .maximum(500)
                        .defaultVal(DEFAULT_MAX_RESULTS))
                .required("host_key", "pattern", "path")
                .build();
    }

    @Override
    public long timeoutMs() { return 90_000L; }

    @Override
    public String promptSnippet() {
        return "remote_grep — 在远程服务器目录下搜索文件内容（只读免确认，搜日志/配置优先于 run_ssh grep）。";
    }

    @Override
    public String resolveCommandText(JsonNode input) {
        // 交给权限引擎/审计留痕：实际会执行的命令文本
        return buildCommand(input.path("pattern").asText(""), input.path("path").asText(""),
                input.path("include").asText(""), input.path("exclude").asText(""),
                input.path("ignore_case").asBoolean(false));
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String pattern = input.path("pattern").asText("");
        String path = input.path("path").asText("");
        String include = input.path("include").asText("");
        String exclude = input.path("exclude").asText("");
        boolean ignoreCase = input.path("ignore_case").asBoolean(false);
        int maxResults = Math.min(input.path("max_results").asInt(DEFAULT_MAX_RESULTS), 500);
        if (pattern.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("pattern 和 path 不能为空");
        }

        String command = buildCommand(pattern, path, include, exclude, ignoreCase);
        String sessionId = support.resolveSession(hostKey);
        ExecResult r = manager.exec(sessionId, command, (int) timeoutMs() / 1000);

        if (r.exitCode() != 0) {
            String msg = (r.stderr() + r.stdout()).trim();
            String lower = msg.toLowerCase();
            if ((lower.contains("not found") && (lower.contains("grep") || lower.contains("command")))
                    || r.exitCode() == 127 || r.exitCode() == 126) {
                // 环境缺 grep：模型需要换工具，属于硬错误而非"无匹配"
                throw new IllegalStateException("远程主机没有可用的 grep 命令（退出码 " + r.exitCode()
                        + "，可能是 Windows/精简系统）。请改用 run_ssh 或 sftp_read 逐个查看文件。");
            }
            if (r.exitCode() == 1) {
                // grep 语义：1 = 无任何匹配行，属正常结果，不是错误
                return "[remote_grep] 未找到匹配内容（或路径不存在，请先 sftp_list 核对 path）：" + pattern;
            }
            // 退出码 2 等：路径不存在、权限不足等真实失败 —— 硬错误，避免模型误当"已搜过无结果"
            throw new IllegalStateException("remote_grep 执行失败（grep 退出码 " + r.exitCode() + "）："
                    + (msg.isBlank() ? "无输出" : msg)
                    + "。请核对 path 是否存在、是否有读取权限后重试。");
        }

        List<String> lines = splitLines(r.stdout());
        if (lines.isEmpty()) {
            return "[remote_grep] 未找到匹配内容：" + pattern;
        }
        StringBuilder sb = new StringBuilder("[remote_grep] 匹配 ").append(lines.size()).append(" 行（最多显示 ")
                .append(maxResults).append(" 行）：\n");
        int shown = 0;
        int totalChars = 0;
        for (String line : lines) {
            if (shown >= maxResults) break;
            String t = line.length() <= LINE_LIMIT ? line : line.substring(0, LINE_LIMIT) + "…";
            totalChars += t.length() + 1;
            if (totalChars > TOTAL_LIMIT) break;
            sb.append(t).append('\n');
            shown++;
        }
        if (lines.size() > shown) {
            sb.append("… 其余 ").append(lines.size() - shown).append(" 行未显示，可调大 max_results 或缩小 path。");
        }
        return sb.toString().trim();
    }

    private String buildCommand(String pattern, String path, String include, String exclude, boolean ignoreCase) {
        StringBuilder cmd = new StringBuilder("grep -rn -I --color=never -E ");
        if (ignoreCase) cmd.append("-i ");
        if (include != null && !include.isBlank()) {
            // include 支持逗号分隔多个通配符
            appendGlobs(cmd, "--include=", include);
        }
        if (exclude != null && !exclude.isBlank()) {
            appendGlobs(cmd, "--exclude=", exclude);
        }
        cmd.append(shellQuote(pattern)).append(' ').append(shellQuote(path));
        return cmd.toString();
    }

    /** 把逗号/中文逗号分隔的通配符拼成多个 --include=/--exclude= 参数。 */
    private void appendGlobs(StringBuilder cmd, String opt, String globs) {
        for (String w : globs.split("[,，]")) {
            String t = w.trim();
            if (!t.isEmpty()) cmd.append(opt).append(shellQuote(t)).append(' ');
        }
    }

    /** 单引号包裹 + 转义（防 shell 注入） */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
