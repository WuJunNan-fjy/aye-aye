package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshJobManager;
import com.aye.server.ssh.SshToolSupport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deploy_start_remote 工具 —— 在远程 current 目录内以后台方式启动应用并记录 pid。
 *
 * <p>EXECUTE 类；AI 提供的启动命令只允许三种固定形态且不含任何 shell 元字符，
 * 后端包裹 nohup/重定向/pid 记录，启动操作仍须按当前权限模式审批。
 */
public final class DeployStartRemoteTool implements Tool {

    /** AI 启动命令中禁止的字符（防注入；构造的外壳不受此限）。 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "[;|&$><`'\"\n\r]|\\.\\.");

    /** 安全参数片段。 */
    private static final String SAFE_ARGS = "[A-Za-z0-9._=:/@\\- ]*";

    private final SshToolSupport support;
    private final SshJobManager jobs;
    private final DeployRunStore runs;

    public DeployStartRemoteTool(SshToolSupport support, SshJobManager jobs,
                                 DeployRunStore runs) {
        this.support = support;
        this.jobs = jobs;
        this.runs = runs;
    }

    @Override
    public String name() { return "deploy_start_remote"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "在服务器后台启动应用（nohup），自动记录 pid。启动命令仅支持："
                + "java -jar 应用目录内的 jar；node 应用目录内的 js；python3 -m http.server 端口。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("目标主机标识"))
                .property("appDir", JsonSchema.string().description("应用目录"))
                .property("startCommand", JsonSchema.string()
                        .description("启动命令（三种固定形态，不含 shell 符号）"))
                .property("port", JsonSchema.number().description("应用监听端口"))
                .required("host_key", "appDir", "startCommand", "port")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "deploy_start_remote — 后台启动应用并记 pid。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String appDir = DeployRemoteSupport.requireAppDir(input.path("appDir").asText(""));
        String startCommand = input.path("startCommand").asText("").trim();
        int port = DeployRemoteSupport.requirePort(input.path("port").asInt(0));

        validateStartCommand(startCommand, appDir, port);

        String command = buildStartCommand(appDir, startCommand);

        SshJobManager.JobView v =
                DeployRemoteSupport.runOneShot(support, jobs, hostKey, command, "start");
        boolean ok = v.status() == SshJobManager.Status.DONE && v.exitCode() == 0;

        Long pid = parsePid(v.stdout());
        Long existingPid = parseExistingPid(v.stdout());
        boolean alreadyRunning = existingPid != null;
        DeployRunStore.DeployRun run = runs.latestBySession(ctx.sessionId());
        if (run != null && pid != null) runs.setRemotePid(run.id(), pid);

        if (alreadyRunning) {
            return "应用已在运行（pid=" + existingPid + "），未重复启动。\n"
                    + "如需重启，请先停止现有进程（或删除 " + appDir + "/run.pid 后确认旧进程已退出）。";
        }
        return "远程启动" + (ok ? "命令已执行" : "失败") + "\n"
                + "启动命令：" + startCommand + "\n"
                + "pid=" + (pid == null ? "未知" : pid) + "\n"
                + "日志：" + appDir + "/console.log\n"
                + "exit=" + v.exitCode() + "\n"
                + (ok ? "下一步：deploy_probe_remote 探活；若失败读 console.log 尾部排错。"
                        : "请检查上方输出，常见原因：端口占用、运行时缺失、jar/js 路径错误。");
    }

    /** 校验三种形态。 */
    private static void validateStartCommand(String cmd, String appDir, int port) {
        if (cmd.isEmpty()) {
            throw new IllegalArgumentException("startCommand 不能为空");
        }
        if (FORBIDDEN.matcher(cmd).find()) {
            throw new IllegalArgumentException(
                    "startCommand 含 shell 元字符或相对路径跳转，拒绝执行：" + cmd);
        }
        String dir = Pattern.quote(appDir + "/current/");
        Pattern javaJar = Pattern.compile(
                "^java -jar " + dir + "[A-Za-z0-9._/-]+\\.jar(?: " + SAFE_ARGS + ")?$");
        Pattern nodeJs = Pattern.compile(
                "^node " + dir + "[A-Za-z0-9._/-]+\\.js(?: " + SAFE_ARGS + ")?$");
        Pattern httpServer = Pattern.compile(
                "^python3 -m http\\.server " + port + "$");

        if (!javaJar.matcher(cmd).matches()
                && !nodeJs.matcher(cmd).matches()
                && !httpServer.matcher(cmd).matches()) {
            throw new IllegalArgumentException(
                    "startCommand 不被支持（仅支持 java -jar、node、python3 -m http.server 三种形态，"
                            + "且路径必须在 " + appDir + "/current 内）：" + cmd);
        }
    }

    /**
     * 构造远程启动外壳：
     * <ul>
     *   <li>启动前检查 run.pid 中的旧进程是否仍存活（kill -0），存活则拒绝重复启动；</li>
     *   <li>日志用 {@code >>} 追加，避免每次重启覆盖历史 console.log；</li>
     *   <li>cd 与启动用「;」分隔，确保 {@code &} 只作用于 nohup 简单命令，
     *       {@code $!} 就是应用自身 PID（而非整条 AND-list 的子 shell PID）。</li>
     * </ul>
     */
    static String buildStartCommand(String appDir, String startCommand) {
        String pidFile = DeployRemoteSupport.quote(appDir + "/run.pid");
        String logFile = DeployRemoteSupport.quote(appDir + "/console.log");
        return "cd " + DeployRemoteSupport.quote(appDir + "/current")
                + "; if [ -f " + pidFile + " ]; then OLD=$(cat " + pidFile + "); "
                + "if kill -0 \"$OLD\" 2>/dev/null; then echo already_running pid=$OLD; exit 3; fi; fi"
                + "; nohup " + startCommand + " >> " + logFile
                + " 2>&1 & echo $! > " + pidFile
                + "; echo started pid=$(cat " + pidFile + ")";
    }

    /** 仅认新启动输出 "started pid="，避免把预检提示的旧 pid 误记账。 */
    private static Long parsePid(String stdout) {
        if (stdout == null) return null;
        Matcher m = Pattern.compile("started pid=(\\d+)").matcher(stdout);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static Long parseExistingPid(String stdout) {
        if (stdout == null) return null;
        Matcher m = Pattern.compile("already_running pid=(\\d+)").matcher(stdout);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}
