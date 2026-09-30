package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.DestructiveTag;
import com.aye.ai.permission.PermissionEngine;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.util.List;

/**
 * sftp_remove 工具 —— 删除远程文件或目录（目录等价 rm -rf，破坏性强）。
 *
 * <p>破坏性提示 {@link DestructiveTag#RECURSIVE_FORCE_DELETE} 交给权限引擎
 * 做破坏性决策；命令文本交给权限引擎扫描路径本身的危险特征。
 */
public final class SftpRemoveTool implements Tool {

    private final SshToolSupport support;
    private final SftpService sftp;

    public SftpRemoveTool(SshToolSupport support, SftpService sftp) {
        this.support = support;
        this.sftp = sftp;
    }

    @Override
    public String name() { return "sftp_remove"; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public String description() {
        return "删除远程服务器上的文件或目录（SFTP）。删除目录会递归删除全部内容（等价 rm -rf），"
                + "破坏性强，仅在用户明确要求删除时使用。host_key 必须先用 list_hosts 查询。"
                + "安全红线：/、/etc 及 /usr /boot /bin /sbin /lib /lib64 /proc /sys /dev 等关键系统目录"
                + "会被直接拒绝；/etc 子路径与 /home、/root、/var、/opt、/srv、/usr/local 等高风险目录"
                + "需用户强制确认。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("稳定主机标识（先用 list_hosts 查询）"))
                .property("path", JsonSchema.string().description("要删除的远程文件或目录绝对路径"))
                .property("directory", JsonSchema.bool().description("是否为目录（目录将递归删除）"))
                .required("host_key", "path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "sftp_remove — 删除远程文件/目录（目录递归删除，破坏性强，务必先与用户确认路径）。";
    }

    @Override
    public String resolveCommandText(JsonNode input) {
        // 把路径交给权限引擎做破坏性扫描
        return "rm -rf '" + input.path("path").asText("") + "'";
    }

    @Override
    public List<DestructiveTag> computeDestructiveHints(JsonNode input, ToolContext ctx) {
        return List.of(DestructiveTag.RECURSIVE_FORCE_DELETE);
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String path = input.path("path").asText("");
        boolean directory = input.path("directory").asBoolean(false);
        if (path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        // fail-closed 兜底：与权限引擎 sftp-remove-critical-path 规则共用同一分级（含根目录），
        // 即使引擎规则评估异常或处于 TRUSTED(yolo) 通道，关键系统路径仍在此被硬挡。
        String normalizedPath = PermissionEngine.normalizePosixPath(path);
        if (PermissionEngine.classifyRemoteDeletion(path) == PermissionEngine.CriticalPathLevel.DENY) {
            throw new IllegalArgumentException(
                    "安全拦截：不允许删除远程主机关键系统路径（" + normalizedPath + "）。"
                            + "删除可能导致主机变砖或 SSH 失联且无法恢复；"
                            + "如确需操作，请在远程主机上由人工执行。");
        }

        long hostId = support.resolveHostId(hostKey);
        String sessionId = support.resolveSession(hostId);
        sftp.remove(sessionId, path, directory);
        return "已删除 " + (directory ? "目录（含全部内容）" : "文件") + " " + path;
    }
}
