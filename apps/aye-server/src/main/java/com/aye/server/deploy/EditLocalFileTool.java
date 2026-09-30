package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.local.LocalAttachmentRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * edit_local_file 工具 —— 构建排错时修改工作区内的文本文件（配置、源码）。
 *
 * <p>WRITE 类：每次调用都经用户确认（确认卡展示路径与原因）。
 * 路径必须在会话授权根（工作区）内；只处理已存在的文本文件（≤1MB），
 * 整文件覆盖写入。
 */
public final class EditLocalFileTool implements Tool {

    private static final long MAX_BYTES = 1024L * 1024;

    private final LocalAttachmentRegistry registry;

    public EditLocalFileTool(LocalAttachmentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() { return "edit_local_file"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "修改当前项目（工作区）内的一个文本文件（构建报错时修正配置或源码）。"
                + "每次修改都会先请你确认。只允许修改项目目录内已存在、不超过 1MB 的文本文件，"
                + "需要提供文件完整的新内容。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("path", JsonSchema.string().description("要修改的文件绝对路径（必须在项目目录内）"))
                .property("reason", JsonSchema.string().description("修改原因（大白话），展示给用户确认"))
                .property("content", JsonSchema.string().description("文件修改后的完整内容"))
                .required("path", "reason", "content")
                .build();
    }

    @Override
    public long timeoutMs() { return 30_000L; }

    @Override
    public String promptSnippet() {
        return "edit_local_file — 修改项目内文本文件（构建排错），每次需用户确认。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String rawPath = input.path("path").asText("");
        String reason = input.path("reason").asText("");
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空（需要向用户说明修改原因）");
        }
        if (!registry.isAllowed(ctx.sessionId(), rawPath)) {
            throw new SecurityException("路径不在当前项目（工作区）授权范围内，拒绝修改：" + rawPath);
        }
        Path p = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(p) || Files.isDirectory(p)) {
            throw new IllegalArgumentException("文件不存在或不是普通文件（本工具只改已有文件）：" + p);
        }
        long sizeBefore = Files.size(p);
        if (sizeBefore > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 1MB，不适合整文件修改：" + p);
        }
        byte[] bytes = input.path("content").asText("").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("新内容超过 1MB 上限");
        }
        Files.write(p, bytes);

        return "文件已修改：" + p + "\n"
                + "大小：" + sizeBefore + " → " + bytes.length + " 字节\n"
                + "原因：" + reason + "\n"
                + "建议：重新执行构建命令验证修改是否解决问题。";
    }
}
