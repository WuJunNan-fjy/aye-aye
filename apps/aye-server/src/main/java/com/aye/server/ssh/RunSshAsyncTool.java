package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

/**
 * run_ssh_async 工具 —— 异步执行可能运行很久的远程命令（构建、打包、大日志导出、
 * 批量任务、升级安装等预计超过 2 分钟的场景）。
 *
 * <p>与 {@link SshTool}（run_ssh，同步 120s 超时）互补：本工具只负责启动，
 * 立即返回 job_id；命令在 {@link SshJobManager} 的独立 daemon 线程上继续运行，
 * LLM 随后用 {@code ssh_job_poll} 长轮询输出与退出码。权限链与 run_ssh 完全一致：
 * 同属 EXECUTE 类，{@link #resolveCommandText} 把命令交权限引擎做破坏性扫描，
 * 写/改/重启类命令照样需要用户确认。
 */
public final class RunSshAsyncTool implements Tool {

    private final SshToolSupport support;
    private final SshJobManager jobs;

    public RunSshAsyncTool(SshToolSupport support, SshJobManager jobs) {
        this.support = support;
        this.jobs = jobs;
    }

    @Override
    public String name() { return "run_ssh_async"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "通过 SSH 在远程服务器【异步】执行一条可能运行很久的命令（预计超过 2 分钟的"
                + "构建、打包、编译、大日志导出/分析、批量处理、安装升级等），立即返回 job_id，"
                + "不受 run_ssh 的 120 秒同步超时限制；随后必须用 ssh_job_poll 轮询输出与退出码"
                + "（支持 wait_seconds 长轮询），直到 status=DONE/FAILED。"
                + "短命令（查看状态、秒级完成的操作）仍应直接用 run_ssh，不要滥用异步。"
                + "host_key 规则与 run_ssh 相同：先用 list_hosts 查询，仅对已连接/可自动建连的主机调用；"
                + "单任务最长运行 60 分钟，结束后结果保留 30 分钟。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("host_key", JsonSchema.string().description("目标主机稳定标识（list_hosts 返回的 host_key）"))
                .property("command", JsonSchema.string().description("要异步执行的 shell 命令"))
                .property("tag", JsonSchema.string().description("可选：便于识别的短标签（如 build、deploy），轮询时原样回显"))
                .required("host_key", "command")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "run_ssh_async — 异步执行长命令（>2 分钟的构建/打包/批量任务），"
                + "立即返回 job_id，再用 ssh_job_poll 长轮询；短命令仍用 run_ssh。";
    }

    @Override
    public String promptGuidelines() {
        return """
            - 预计 2 分钟内完成的命令一律用 run_ssh（同步拿结果，轮询徒增往返）。
            - 预计很久的命令（mvn/npm build、make、大目录打包、全量日志分析、apt/yum 升级等）才用 run_ssh_async。
            - 启动后立刻调用 ssh_job_poll 并带 wait_seconds=20 长轮询；返回 RUNNING 就继续轮询，
              不要重复启动同一条命令，也不要改用 run_ssh 再跑一遍。
            - 一条异步命令保持单一目的；权限规则与 run_ssh 相同（写/重启类仍需用户确认）。""";
    }

    @Override
    public String resolveCommandText(JsonNode input) {
        // 与 run_ssh 相同：命令文本交权限引擎做破坏性扫描（rm -rf / shutdown 等）
        return input.path("command").asText("");
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        String hostKey = input.path("host_key").asText("");
        String command = input.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        JsonNode tagNode = input.path("tag");
        String tag = tagNode.isTextual() && !tagNode.asText().isBlank() ? tagNode.asText() : null;

        String sessionId = support.resolveSession(hostKey);
        String jobId = jobs.start(sessionId, command, tag);

        StringBuilder sb = new StringBuilder();
        sb.append("[async_job=started]\n");
        sb.append("job_id=").append(jobId).append('\n');
        if (tag != null) sb.append("tag=").append(tag).append('\n');
        sb.append("command=").append(command).append('\n');
        sb.append("任务已在后台运行（最长 60 分钟，结束后结果保留 30 分钟）。\n");
        sb.append("下一步：调用 ssh_job_poll（job_id=").append(jobId)
          .append(", wait_seconds=20）等待结果；返回 RUNNING 就继续轮询，DONE/FAILED 后停止。");
        return sb.toString();
    }
}
