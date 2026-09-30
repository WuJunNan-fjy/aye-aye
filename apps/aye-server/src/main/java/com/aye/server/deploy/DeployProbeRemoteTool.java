package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshJobManager;
import com.aye.server.ssh.SshToolSupport;

/**
 * deploy_probe_remote 工具 —— 远程探活：HTTP 状态码 + pid 进程状态。
 *
 * <p>READ 类、免确认。curl 只访问 127.0.0.1。
 */
public final class DeployProbeRemoteTool implements Tool {

    private final SshToolSupport support;
    private final SshJobManager jobs;

    public DeployProbeRemoteTool(SshToolSupport support, SshJobManager jobs) {
        this.support = support;
        this.jobs = jobs;
    }

    @Override
    public String name() { return "deploy_probe_remote"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "检查远程应用是否启动成功：HTTP 探活（本机 127.0.0.1 端口）返回 http_code，"
                + "并检查 run.pid 对应进程是否存活。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("目标主机标识"))
                .property("appDir", JsonSchema.string().description("应用目录"))
                .property("port", JsonSchema.number().description("应用监听端口"))
                .required("host_key", "appDir", "port")
                .build();
    }

    @Override
    public long timeoutMs() { return 30_000L; }

    @Override
    public String promptSnippet() {
        return "deploy_probe_remote — HTTP + pid 探活。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String appDir = DeployRemoteSupport.requireAppDir(input.path("appDir").asText(""));
        int port = DeployRemoteSupport.requirePort(input.path("port").asInt(0));

        String q = DeployRemoteSupport.quote(appDir + "/run.pid");
        String command = "curl -s -o /dev/null -w 'http_code=%{http_code}' --max-time 3 "
                + "http://127.0.0.1:" + port + "/; echo; "
                + "if [ -f " + q + " ]; then pid=$(cat " + q + "); "
                + "if kill -0 \"$pid\" 2>/dev/null; then echo \"pid=$pid alive\"; "
                + "else echo \"pid=$pid dead\"; fi; else echo no-pid-file; fi";

        SshJobManager.JobView v =
                DeployRemoteSupport.runOneShot(support, jobs, hostKey, command, "probe");
        String out = v.stdout();
        boolean httpOk = out.contains("http_code=200");
        return "探活结果（端口 " + port + "）：\n" + out.trim() + "\n"
                + (httpOk ? "应用已正常响应。"
                        : "应用未正常响应：读取日志排错（" + appDir + "/console.log 尾部）。");
    }
}
