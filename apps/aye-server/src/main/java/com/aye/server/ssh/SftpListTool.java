package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * sftp_list 工具 —— 列出远程服务器目录内容。
 */
public final class SftpListTool implements Tool {

    /** 单次列表输出的条目上限，防止 /usr/bin 等大目录吃满上下文；超出部分提示缩小范围。 */
    private static final int MAX_ENTRIES = 200;
    private static final DateTimeFormatter MTIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpListTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_list"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "列出远程服务器上某个目录的内容（SFTP）。返回文件/子目录的名称、类型、大小、修改时间。"
                + "查看目录结构时优先于 run_ssh 执行 ls（结构化输出、只读免确认）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("远程目录绝对路径，如 /var/log"))
                .required("host_key", "path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_list — 列出远程服务器目录内容（只读免确认，查看目录优先于 run_ssh ls）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        String sessionId = support.resolveSession(hostKey);

        List<Map<String, Object>> files = sftp.list(sessionId, path);
        if (files.isEmpty()) {
            return "目录为空：" + path;
        }
        int total = files.size();
        int shown = Math.min(total, MAX_ENTRIES);
        StringBuilder sb = new StringBuilder("目录 ").append(path)
                .append("（共 ").append(total).append(" 项")
                .append(total > MAX_ENTRIES ? "，仅显示前 " + MAX_ENTRIES + " 项" : "")
                .append("；时间为服务器本地时区）：\n");
        for (int i = 0; i < shown; i++) {
            Map<String, Object> f = files.get(i);
            boolean dir = "directory".equals(f.get("type"));
            sb.append(dir ? "[DIR ] " : "[FILE] ")
                    .append(f.get("name"))
                    .append(dir ? "/" : "")
                    .append("  ")
                    .append(SshToolSupport.formatBytes(((Number) f.get("size")).longValue()))
                    .append("  ")
                    .append(MTIME_FMT.format(Instant.ofEpochMilli(((Number) f.get("modifiedTime")).longValue())))
                    .append('\n');
        }
        if (total > MAX_ENTRIES) {
            sb.append("…（已截断）请进入具体子目录查看，或用 remote_grep 按文件名/内容过滤。");
        }
        return sb.toString();
    }
}
