package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshJobManager;
import com.aye.server.ssh.SshToolSupport;

/**
 * deploy_extract_remote 工具 —— 在远程应用目录内解压已上传的归档。
 *
 * <p>EXECUTE 类；命令由后端按校验后的参数构造（应用目录/归档名严格白名单），
 * 解压须按当前权限模式审批。tar.gz 用 tar -xzf，.zip 用 unzip；解压目标固定为
 * {@code <appDir>/current}。
 */
public final class DeployExtractRemoteTool implements Tool {

    private final SshToolSupport support;
    private final SshJobManager jobs;

    public DeployExtractRemoteTool(SshToolSupport support, SshJobManager jobs) {
        this.support = support;
        this.jobs = jobs;
    }

    @Override
    public String name() { return "deploy_extract_remote"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "在服务器上把已上传的归档（.tar.gz 或 .zip）解压到应用的 current 目录。"
                + "目录与文件名均做安全校验，命令由系统构造。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("目标主机标识"))
                .property("appDir", JsonSchema.string().description("应用目录（如 /opt/aye-apps/demo）"))
                .property("archive", JsonSchema.string().description("归档文件名（如 demo.tar.gz）"))
                .required("host_key", "appDir", "archive")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "deploy_extract_remote — 远程解压归档到 current。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String appDir = DeployRemoteSupport.requireAppDir(input.path("appDir").asText(""));
        String archive = input.path("archive").asText("");
        DeployRemoteSupport.requireName(archive);

        String archivePath = appDir + "/" + archive;
        String currentDir = appDir + "/current";
        // Zip Slip 防护：解压前先列出全部条目，拒绝绝对路径或含 ".." 段的条目，
        // 防止恶意归档借 ../ 写到 current 目录之外。模式用单引号包裹（远程 shell 解析）。
        String traversalGuard = "if printf '%s\\n' \"$LIST\" | grep -Eq '(^|/)\\.\\.(/|$)|^/'; "
                + "then echo '归档包含路径穿越条目（绝对路径或 ..），已拒绝解压'; exit 3; fi";
        String listCmd = archive.endsWith(".zip")
                ? "unzip -Z1 " + DeployRemoteSupport.quote(archivePath)
                : "tar -tzf " + DeployRemoteSupport.quote(archivePath);
        String extractCmd = archive.endsWith(".zip")
                ? "unzip -o " + DeployRemoteSupport.quote(archivePath)
                    + " -d " + DeployRemoteSupport.quote(currentDir)
                : "tar -xzf " + DeployRemoteSupport.quote(archivePath)
                    + " -C " + DeployRemoteSupport.quote(currentDir);
        String command = "mkdir -p " + DeployRemoteSupport.quote(currentDir)
                + " && LIST=$(" + listCmd + ")"
                + " && " + traversalGuard
                + " && " + extractCmd;

        SshJobManager.JobView v =
                DeployRemoteSupport.runOneShot(support, jobs, hostKey, command, "extract");
        boolean ok = v.status() == SshJobManager.Status.DONE && v.exitCode() == 0;
        return "远程解压" + (ok ? "完成" : "失败") + "：" + archivePath + " → " + appDir + "/current\n"
                + "exit=" + v.exitCode() + "\n"
                + (v.stderr().isEmpty() ? "" : "stderr：" + v.stderr() + "\n")
                + (ok ? "下一步：deploy_start_remote 启动应用。"
                        : "请根据输出检查归档格式/内容（服务器是否安装 unzip/tar）。");
    }
}
