package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.nio.charset.StandardCharsets;

/**
 * sftp_write 工具 —— 在远程服务器上写入（覆盖）文本文件。
 */
public final class SftpWriteTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpWriteTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_write"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "在远程服务器上写入（整体覆盖）一个文本文件（SFTP）。用于新建文件或对文件做整体重写；"
                + "只改少量内容时优先用 sftp_edit（精确替换，避免整文件重发遗漏）。"
                + "覆盖已存在文件前必须先 sftp_read 获取当前内容。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("远程文件绝对路径"))
                .property("content", JsonSchema.string().description("要写入的完整内容（覆盖原文件）"))
                .required("host_key", "path", "content")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_write — 在远程服务器上写入/覆盖文本文件（写类，谨慎使用）。";
    }

    @Override
    public String promptGuidelines() {
        return """
                ### sftp_write
                - 小改动一律优先 sftp_edit：整文件覆盖要求你重发全文，遗漏任何一段都会直接丢失原内容。
                - 覆盖已存在文件前必须先 sftp_read，基于刚读到的真实内容构造完整新内容，不要凭记忆重写。
                - 新建文件、或对文件做整体重构时才使用本工具。
                - 写完按返回的验证提示复查（语法检查/重新读取关键片段），不要只凭"命令没报错"声称完成。""";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        String content = input.path("content").asText("");

        long hostId = support.resolveHostId(hostKey);
        String sessionId = support.resolveSession(hostId);
        sftp.writeText(sessionId, path, content);
        return SshToolSupport.withVerifyHint(
                "已写入 " + content.getBytes(StandardCharsets.UTF_8).length + " 字节到 " + path,
                SshToolSupport.VERIFY_HINT_WRITE);
    }
}
