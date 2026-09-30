package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

/**
 * sftp_read 工具 —— 读取远程服务器上的小文本文件。
 */
public final class SftpReadTool implements Tool {

    private static final int DEFAULT_MAX_BYTES = 10 * 1024; // 默认 10KB

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpReadTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_read"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "读取远程服务器上的文本文件内容（SFTP）。默认最多读 10KB，适合查看日志片段/配置文件；"
                + "看日志尾部用 position=tail（服务端偏移定位，不拉整个文件）；"
                + "大文件请用前端 SFTP 面板下载。"
                + "查看已知路径的文本文件时优先于 run_ssh 执行 cat/tail/head（只读免确认、有长度保护）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("远程文件绝对路径"))
                .property("position", JsonSchema.string()
                        .description("读取位置：head 从头读（默认）；tail 读文件尾部（看最新日志）")
                        .enumValues("head", "tail"))
                .property("max_bytes", JsonSchema.integer().description("最大读取字节数，默认 10240（10KB），上限 1MB"))
                .required("host_key", "path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_read — 读取远程服务器上的小文本文件（只读免确认，优先于 run_ssh cat/tail/head，默认 10KB 上限）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        boolean tail = "tail".equalsIgnoreCase(input.path("position").asText("head"));
        int maxBytes = input.path("max_bytes").asInt(DEFAULT_MAX_BYTES);
        maxBytes = (int) Math.max(1, Math.min(maxBytes, SshToolSupport.MAX_FILE_BYTES));

        String sessionId = support.resolveSession(hostKey);
        return tail
                ? sftp.readTailText(sessionId, path, maxBytes)
                : sftp.readText(sessionId, path, maxBytes);
    }
}
