package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.util.Base64;

/**
 * sftp_upload 工具 —— 上传文件到远程服务器。
 *
 * <p>内容以 base64 传入，避免二进制直接进 LLM 上下文；单文件上限 1MB（{@link SshToolSupport#MAX_FILE_BYTES}），
 * 更大的文件请走前端 SFTP 面板。
 */
public final class SftpUploadTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpUploadTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_upload"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "上传文件到远程服务器（SFTP）。文件内容用 base64 编码传入，单文件上限 1MB，"
                + "仅适合小文本（配置片段、小脚本）；不要用它传输二进制大文件，那类文件请让用户通过前端 SFTP 面板上传。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("dir", JsonSchema.string().description("远程目标目录绝对路径"))
                .property("filename", JsonSchema.string().description("目标文件名"))
                .property("content_base64", JsonSchema.string().description("文件内容的 base64 编码"))
                .property("size_bytes", JsonSchema.integer().description("原始文件字节数（可选，用于校验）"))
                .required("host_key", "dir", "filename", "content_base64")
                .build();
    }

    @Override
    public long timeoutMs() { return 120_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_upload — 上传文件到远程服务器（写类，谨慎使用）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String dir = input.path("dir").asText("");
        String filename = input.path("filename").asText("");
        String contentBase64 = input.path("content_base64").asText("");

        // 参数错误属于硬错误（isError=true），避免模型误判上传成功后继续后续部署步骤
        final byte[] data;
        try {
            data = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "content_base64 不是合法的 Base64 编码，请重新编码后再调用本工具。");
        }
        if (data.length > SshToolSupport.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "文件超过 1MB 上限（" + SshToolSupport.formatBytes(data.length)
                            + "）。本工具仅用于小文本；更大的文件请让用户通过前端 SFTP 面板上传。");
        }

        long hostId = support.resolveHostId(hostKey);
        String sessionId = support.resolveSession(hostId);
        sftp.upload(sessionId, dir, data, filename);
        return SshToolSupport.withVerifyHint(
                "已上传 " + filename + "（" + SshToolSupport.formatBytes(data.length) + "）到 " + dir,
                SshToolSupport.VERIFY_HINT_UPLOAD);
    }
}
