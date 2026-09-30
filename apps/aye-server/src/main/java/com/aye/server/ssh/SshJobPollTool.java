package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.ssh.SshJobManager.JobView;
import com.aye.server.ssh.SshJobManager.Status;

/**
 * ssh_job_poll 工具 —— 查询 run_ssh_async 启动的后台作业。
 *
 * <p>只读作业状态、无副作用（READ 类，并发安全、免确认）；支持 wait_seconds 长轮询：
 * 调用期间作业结束会立即返回，否则等到超时返回当前输出快照。LLM 据此用最少的往返
 * 盯住一条长命令，而不是忙等或重跑。单次 stdout/stderr 各截取最后约 1.4 万字符
 * （盯构建/日志尾部）；总量超过内部缓冲时结果自带省略标记。
 */
public final class SshJobPollTool implements Tool {

    /**
     * 回给 LLM 的单流字符上限：stdout/stderr 双流合计需低于 ToolExecutor 的 30k 结果上限，
     * 避免触发「完整内容存本地临时文件」转储（轮询场景会反复写盘且提示语对 LLM 无意义）。
     */
    private static final int OUTPUT_MAX_CHARS = 14_000;

    private final SshJobManager jobs;

    public SshJobPollTool(SshJobManager jobs) {
        this.jobs = jobs;
    }

    @Override
    public String name() { return "ssh_job_poll"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "查询 run_ssh_async 启动的后台命令作业：返回状态（RUNNING/DONE/FAILED）、"
                + "已运行时长、退出码与目前的 stdout/stderr（每路尾部约 1.4 万字符）。"
                + "可带 wait_seconds 长轮询（0-25，默认 20）：作业在等待期间结束会立即返回。"
                + "RUNNING 时应继续轮询（不要重复启动命令）；DONE/FAILED 后本作业仍可重复查看 30 分钟。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("job_id", JsonSchema.string().description("run_ssh_async 返回的 job_id"))
                .property("wait_seconds",
                        JsonSchema.number().description("可选：长轮询等待秒数（0-25，默认 20）"))
                .required("job_id")
                .build();
    }

    @Override
    public long timeoutMs() { return 30_000L; }

    @Override
    public String promptSnippet() {
        return "ssh_job_poll — 长轮询 run_ssh_async 作业状态与输出（wait_seconds=20），"
                + "RUNNING 继续轮询，DONE/FAILED 停止。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String jobId = input.path("job_id").asText("");
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("job_id 不能为空");
        }
        int waitSec = input.path("wait_seconds").asInt(20);
        if (waitSec < 0) waitSec = 0;
        if (waitSec > 25) waitSec = 25;

        JobView v = jobs.snapshot(jobId, waitSec * 1000L, OUTPUT_MAX_CHARS);
        if (v == null) {
            throw new IllegalArgumentException(
                    "作业不存在：job_id 错误、结果已过期（结束后保留 30 分钟），或服务已重启（作业仅存内存）。");
        }

        StringBuilder sb = new StringBuilder();
        if (v.tag() != null) sb.append("tag=").append(v.tag()).append('\n');
        sb.append("status=").append(v.status().name())
          .append(" elapsed=").append(String.format("%.1f", v.elapsedMs() / 1000.0)).append('s');
        if (v.status() == Status.DONE) sb.append(" exit=").append(v.exitCode());
        sb.append('\n');
        if (v.error() != null) sb.append("error=").append(v.error()).append('\n');
        sb.append("command=").append(v.command()).append('\n');

        if (!v.stdout().isEmpty()) {
            sb.append("--- stdout ---\n").append(v.stdout());
            if (!v.stdout().endsWith("\n")) sb.append('\n');
        }
        if (!v.stderr().isEmpty()) {
            sb.append("--- stderr ---\n").append(v.stderr());
            if (!v.stderr().endsWith("\n")) sb.append('\n');
        }
        if (v.stdout().isEmpty() && v.stderr().isEmpty()) {
            sb.append("（暂无输出）\n");
        }
        if (v.status() == Status.RUNNING) {
            sb.append("任务仍在运行：请再次调用 ssh_job_poll（job_id=").append(jobId)
              .append(", wait_seconds=20）继续等待，不要重复执行原命令。");
        } else if (v.status() == Status.DONE && v.exitCode() != 0) {
            sb.append("命令已结束但退出码非 0：按上面的输出排查原因。");
        } else if (v.status() == Status.DONE) {
            sb.append("命令已成功结束，无需继续轮询。");
        } else {
            sb.append("作业失败：先读上方 stderr/输出尾部定位根因（必要时再 poll 一次拿全尾部），"
                    + "不要不加修改地原样重跑；确认原因并修正命令后，才重新用 run_ssh_async 启动。");
        }
        return sb.toString();
    }
}
