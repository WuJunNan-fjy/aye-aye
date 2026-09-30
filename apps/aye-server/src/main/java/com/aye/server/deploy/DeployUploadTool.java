package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcraft.jsch.SftpProgressMonitor;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.local.LocalAttachmentRegistry;
import com.aye.server.ssh.SftpService;
import com.aye.server.ssh.SshToolSupport;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * deploy_upload 工具 —— 把工作区内的产物以流式 SFTP 直传到目标主机。
 *
 * <p>文件不出后端、只开一次 SFTP put（内存与包大小无关）。EXECUTE 类；
 * ChatService 回调对本工具自动批准（参数在此严格校验）：
 * <ul>
 *   <li>localPath 必须存在且在会话授权根内；</li>
 *   <li>filename 只含安全字符；remoteDir 必须位于 aye-apps 目录约定内。</li>
 * </ul>
 */
public final class DeployUploadTool implements Tool {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SAFE_REMOTE_DIR = Pattern.compile(
            "^(?:/opt|/root|~|/home/[a-z_][a-z0-9_-]*)?/aye-apps/[A-Za-z0-9_-]+/?$");

    private final SshToolSupport support;
    private final SftpService sftp;
    private final LocalAttachmentRegistry registry;
    private final DeployRunStore runs;
    private final WorkspaceStore workspaces;

    public DeployUploadTool(SshToolSupport support, SftpService sftp,
                            LocalAttachmentRegistry registry, DeployRunStore runs,
                            WorkspaceStore workspaces) {
        this.support = support;
        this.sftp = sftp;
        this.registry = registry;
        this.runs = runs;
        this.workspaces = workspaces;
    }

    @Override
    public String name() { return "deploy_upload"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "把当前项目里构建好的产物（如 zip 包、jar 包）直接上传到目标服务器，"
                + "流式传输、不占额外内存。远程目录使用约定的 aye-apps 目录；"
                + "上传前必须已经过用户确认产物。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("localPath", JsonSchema.string().description("本地产物绝对路径（项目目录内）"))
                .property("host_key", JsonSchema.string().description("目标主机标识（list_hosts 返回）"))
                .property("remoteDir",
                        JsonSchema.string().description("远程目录（如 /opt/aye-apps/demo）"))
                .property("filename", JsonSchema.string().description("可选：远程文件名；缺省用本地文件名"))
                .required("localPath", "host_key", "remoteDir")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "deploy_upload — 产物流式直传服务器（aye-apps 目录）。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String rawLocal = input.path("localPath").asText("");
        String hostKey = input.path("host_key").asText("");
        String remoteDir = input.path("remoteDir").asText("").replace('\\', '/');

        if (rawLocal.isBlank() || hostKey.isBlank() || remoteDir.isBlank()) {
            throw new IllegalArgumentException("localPath / host_key / remoteDir 不能为空");
        }
        if (!registry.isAllowed(ctx.sessionId(), rawLocal)) {
            throw new SecurityException("产物不在当前项目（工作区）授权范围内：" + rawLocal);
        }
        Path local = Path.of(rawLocal).toAbsolutePath().normalize();
        if (!Files.exists(local) || Files.isDirectory(local)) {
            throw new IllegalArgumentException("产物文件不存在：" + local);
        }
        if (!SAFE_REMOTE_DIR.matcher(remoteDir).matches()) {
            throw new IllegalArgumentException(
                    "远程目录必须位于 aye-apps 约定目录内（如 /opt/aye-apps/demo）："
                            + remoteDir);
        }
        String filename = input.path("filename").asText(
                local.getFileName().toString());
        if (!SAFE_NAME.matcher(filename).matches()
                || ".".equals(filename) || "..".equals(filename)) {
            throw new IllegalArgumentException("文件名只允许字母数字、点、下划线、短横线，"
                    + "且不能是 . 或 ..：" + filename);
        }

        long hostId = support.resolveHostId(hostKey);
        String sshSessionId = support.resolveSession(hostId);
        CountingMonitor monitor = new CountingMonitor(Files.size(local));
        try (InputStream in = Files.newInputStream(local)) {
            sftp.putStream(sshSessionId, remoteDir, filename, in, monitor);
        }

        // 同步部署账本：补填目标主机、登记产物（本地路径+大小）与远程目录
        DeployRunStore.DeployRun run = runs.latestBySession(ctx.sessionId());
        if (run != null) {
            if (run.hostId() == 0L) runs.setHostId(run.id(), hostId);
            runs.setArtifact(run.id(), local.toString(), monitor.expected());
            runs.setRemoteAppDir(run.id(), remoteDir);
        }

        long sent = monitor.sent();
        return "上传完成：\n"
                + "本地：" + local + "（" + SshToolSupport.formatBytes(sent) + "）\n"
                + "远程：" + joinNoDouble(remoteDir, filename) + "\n"
                + "字节数：" + sent + (sent == monitor.expected() ? "（大小一致）" : "（警告：大小不一致）") + "\n"
                + "下一步：在远程解压并启动（deploy_extract_remote → deploy_start_remote → deploy_probe_remote）。";
    }

    private static String joinNoDouble(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** 进度计数监视器（headless，仅计数）。 */
    private static final class CountingMonitor implements SftpProgressMonitor {
        private final long expected;
        private long sent;
        private boolean ended;

        CountingMonitor(long expected) { this.expected = expected; }

        long sent() { return sent; }
        long expected() { return expected; }

        @Override
        public void init(int op, String src, String dest, long max) {
            this.sent = 0;
            this.ended = false;
        }

        @Override
        public boolean count(long chunk) {
            sent += chunk;
            return true;
        }

        @Override
        public void end() { ended = true; }
    }
}
