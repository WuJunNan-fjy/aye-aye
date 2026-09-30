package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

/**
 * sftp_mkdir 工具 —— 在远程服务器上创建目录（递归，等价 mkdir -p）。
 */
public final class SftpMkdirTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpMkdirTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_mkdir"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "在远程服务器上创建目录（SFTP，递归创建中间层级，等价 mkdir -p）。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("要创建的远程目录绝对路径"))
                .required("host_key", "path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_mkdir — 在远程服务器上创建目录（递归）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");

        String sessionId = support.resolveSession(hostKey);
        sftp.mkdir(sessionId, path);
        return "已创建目录 " + path;
    }
}
