package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.nio.charset.StandardCharsets;

/**
 * sftp_edit 工具 —— 在远程文件上做搜索替换编辑（对标 pi/opencode 的 edit_file 搜索替换流派）。
 *
 * <p>相比 sftp_write 的整体覆盖：只改 {@code old_string} → {@code new_string}，
 * 省 token、可精确控制。找不到 / 多次出现时给出明确提示。
 */
public final class SftpEditTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpEditTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_edit"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "在远程服务器文本文件上做精确搜索替换（只改 old_string → new_string，不整体覆盖文件）。"
                + "用于修改配置文件的某个值、批量改同一片段等。"
                + "old_string 未找到或出现多次时会明确报错，可加 replace_all 全部替换。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（list_hosts 返回的 host_key）"))
                .property("path", JsonSchema.string().description("远程文件绝对路径"))
                .property("old_string", JsonSchema.string()
                        .description("要查找并替换的原文（需与文件内容完全一致，包含缩进/换行）")
                        .minLength(1)
                        .maxLength(20_000))
                .property("new_string", JsonSchema.string()
                        .description("替换后的新文本（可为空字符串表示删除该片段）")
                        .maxLength(20_000))
                .property("replace_all", JsonSchema.bool()
                        .description("true 替换所有出现；false（默认）仅当原文唯一出现时替换"))
                .required("host_key", "path", "old_string", "new_string")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_edit — 远程文件精确搜索替换（改配置/批改片段用，写类，谨慎）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        String oldString = input.path("old_string").asText();
        String newString = input.path("new_string").asText();
        boolean replaceAll = input.path("replace_all").asBoolean(false);
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("old_string 不能为空");
        }

        long hostId = support.resolveHostId(hostKey);
        String sessionId = support.resolveSession(hostId);
        String content = sftp.readText(sessionId, path, (int) SshToolSupport.MAX_FILE_BYTES);
        if (content == null) {
            return "[sftp_edit] 读取失败或文件为空：" + path;
        }

        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return "[sftp_edit] 未找到目标片段（old_string 与文件内容不一致）。建议先用 sftp_read 读取文件核对。";
        }
        if (occurrences > 1 && !replaceAll) {
            return "[sftp_edit] old_string 在文件中出现 " + occurrences + " 次，为避免误改未执行。"
                    + "如需全部替换请加 replace_all=true，或改用更长/更精确的 old_string。";
        }

        String updated = replaceAll ? content.replace(oldString, newString)
                : java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(oldString))
                        .matcher(content).replaceFirst(newString);
        sftp.writeText(sessionId, path, updated);

        int newBytes = updated.getBytes(StandardCharsets.UTF_8).length;
        int oldBytes = content.getBytes(StandardCharsets.UTF_8).length;
        return SshToolSupport.withVerifyHint(
                "[sftp_edit] 已替换 " + (replaceAll ? occurrences : 1) + " 处 → " + path
                + "（" + oldBytes + " → " + newBytes + " 字节）",
                SshToolSupport.VERIFY_HINT_EDIT);
    }

    private static int countOccurrences(String content, String target) {
        int idx = 0;
        int count = 0;
        while ((idx = content.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
