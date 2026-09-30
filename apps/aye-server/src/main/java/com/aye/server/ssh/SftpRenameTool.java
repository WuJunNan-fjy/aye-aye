package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

/**
 * sftp_rename 工具 —— 重命名/移动远程文件或目录（SFTP）。
 *
 * <p>补齐 sftp 文件族的 rename 能力（现有 write/upload/remove/mkdir 之外）。
 */
public final class SftpRenameTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpRenameTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_rename"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "重命名或移动远程服务器上的文件/目录（SFTP rename）。"
                + "例如把 app.conf.bak 改回 app.conf，或把日志移到归档目录。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("原路径（远程绝对路径）"))
                .property("new_path", JsonSchema.string()
                        .description("新路径（远程绝对路径；跨目录即移动）"))
                .required("host_key", "path", "new_path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_rename — 重命名/移动远程文件或目录（写类，谨慎）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        String newPath = input.path("new_path").asText("");
        if (path.isBlank() || newPath.isBlank()) {
            throw new IllegalArgumentException("path 和 new_path 不能为空");
        }

        long hostId = support.resolveHostId(hostKey);
        String sessionId = support.resolveSession(hostId);
        sftp.rename(sessionId, path, newPath);
        return "[sftp_rename] 已重命名：" + path + " → " + newPath;
    }
}
