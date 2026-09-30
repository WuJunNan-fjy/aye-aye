package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshSessionManager.ExecResult;

/**
 * run_ssh 工具 —— 通过 SSH 在远程服务器执行命令。
 *
 * <p>入参只需稳定的 {@code host_key}（先用 list_hosts 查询），凭据从 HostStore /
 * {@link SshSessionManager} 已建立的会话取，LLM 不经手密码。
 * 属于「写类/重类」外部工具：执行类调用经权限引擎分级，写/改/重启类命令需用户确认。
 */
public final class SshTool implements Tool {

    private final SshToolSupport support;
    private final SshSessionManager manager;

    public SshTool(SshToolSupport support, SshSessionManager manager) {
        this.support = support;
        this.manager = manager;
    }

    @Override
    public String name() { return "run_ssh"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "通过 SSH 在远程服务器上执行一条命令并返回 stdout/stderr/退出码。"
                + "用于查看服务器状态、部署应用、管理进程、查看日志等远程运维操作。"
                + "host_key 必须先从 list_hosts 查询；"
                + "仅对状态为 [已连接] 或 [可自动建连] 的主机调用，"
                + "[需用户先连接] 的主机不要尝试执行，应引导用户先在主机管理页连接。"
                + "工具选择：列目录优先用 sftp_list、读文本文件优先用 sftp_read、"
                + "按关键字搜索文件内容优先用 remote_grep（这三个专用只读工具结构化输出且无需确认）；"
                + "ps/ls/df/free/ss/netstat/docker ps、systemctl status、journalctl 等纯查看命令"
                + "可直接用本工具执行（系统可自动识别为只读而免确认），"
                + "写操作（安装、重启、修改配置、删除等）仍需用户确认。"
                + "默认 120 秒同步超时（可用 timeout_sec 在 30~300 秒间调整）；"
                + "预计更久的长命令（构建、打包、大批量日志处理等）改用 run_ssh_async 启动后用 ssh_job_poll 轮询。"
                + "注意：同步超时只会断开 SSH 通道，远端命令可能仍在后台运行（孤儿进程），必要时先 ps 核查再清理。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("目标主机稳定标识（list_hosts 返回的 host_key）"))
                .property("command", JsonSchema.string().description("要执行的 shell 命令"))
                .property("timeout_sec", JsonSchema.integer()
                        .description("同步等待秒数，默认 120，范围 30~300；预计更久的命令用 run_ssh_async")
                        .minimum(30)
                        .maximum(300)
                        .defaultVal(120))
                .required("host_key", "command")
                .build();
    }

    /** 工具框架超时上限：远程命令最长 300s，留 10s 余量回收通道。 */
    @Override
    public long timeoutMs() { return 310_000L; }

    @Override
    public String promptSnippet() {
        return "run_ssh — 通过 SSH 在远程服务器执行命令。"
                + "列目录用 sftp_list、读文件用 sftp_read、搜内容用 remote_grep（免确认）；"
                + "纯查看命令（ps/ls/df/ss/docker ps/systemctl status/journalctl 等）可直接执行免确认，"
                + "写/改/重启类操作需用户确认，谨慎使用并在必要时先说明。";
    }

    @Override
    public String promptGuidelines() {
        return """
            - 只读信息获取优先走专用工具：列目录 → sftp_list，读小文本/配置/日志片段 → sftp_read，
              按关键字搜日志或配置 → remote_grep。它们输出结构化、不受 shell 转义影响，且不会触发确认。
            - 需要 shell 表达能力的查看（ps aux、df -h、free -m、ss -tlnp、netstat、lsof、
              docker ps/logs/stats、systemctl status、journalctl、git status/log、tail/head/cat 等）
              直接用 run_ssh 执行即可：系统会对单条纯查看命令自动免确认。
            - 免确认只适用于"单条、无复合结构"的只读命令；不要把查看和写操作拼在一条命令里
              （如 `ls && systemctl restart x`、`cat a; rm b`），否则整条命令都需要确认。
            - 写操作（安装/卸载、重启服务或主机、改配置、删文件、发版、kill 进程等）仍必须经用户确认：
              命令保持单一目的，不要夹带无关操作；执行前先用一句话说明将做什么、影响范围是什么。
            - 默认 120 秒同步超时（timeout_sec 可在 30~300 秒调整）；预计更久的长命令
              （构建/打包/编译/大批量日志处理）改用 run_ssh_async + ssh_job_poll。
            - 同步超时只会断开 SSH 通道，远端命令可能仍在后台运行（孤儿进程）：超时时不要假设
              命令已终止，先用 ps/pgrep 核查进程状态，确认需要后再清理。
            - 禁止用变量展开、命令替换、eval、sudo 等方式绕过确认；不确定命令是否只读时，按需要确认处理。""";
    }

    @Override
    public String resolveCommandText(JsonNode input) {
        // 把命令文本交给权限引擎做破坏性扫描（rm -rf / git push -f 等）
        return input.path("command").asText("");
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String hostKey = input.path("host_key").asText("");
        String command = input.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        int timeoutSec = input.path("timeout_sec").asInt(120);
        timeoutSec = Math.max(30, Math.min(timeoutSec, 300));
        String sessionId = support.resolveSession(hostKey);
        ExecResult r = manager.exec(sessionId, command, timeoutSec);

        StringBuilder sb = new StringBuilder();
        sb.append("[exit=").append(r.exitCode()).append("]\n");
        if (!r.stdout().isEmpty()) {
            sb.append(r.stdout());
            if (!r.stdout().endsWith("\n")) sb.append('\n');
        }
        if (!r.stderr().isEmpty()) {
            sb.append("[stderr]\n").append(r.stderr());
            if (!r.stderr().endsWith("\n")) sb.append('\n');
        }
        // manager.exec 超时约定：exit=-1 且 stderr 追加 [timeout]
        if (r.exitCode() == -1 && r.stderr() != null && r.stderr().contains("[timeout]")) {
            sb.append("[提示] 命令在 ").append(timeoutSec)
                    .append(" 秒同步超时后通道已断开，但远端进程可能仍在运行（孤儿进程）。"
                            + "不要假设命令已终止：先用 ps/pgrep 核查，必要时清理；"
                            + "后续此类长任务请改用 run_ssh_async。\n");
        }
        return sb.toString();
    }
}
