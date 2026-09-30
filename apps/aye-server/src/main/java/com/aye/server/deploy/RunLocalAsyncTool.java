package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.nio.file.Path;

/**
 * run_local_async 工具 —— 在用户本机（工作区内）异步执行可能运行较久的命令：
 * 安装依赖、构建打包等。立即返回 job_id，随后用 local_job_poll 长轮询。
 *
 * <p>EXECUTE 类；{@link #resolveCommandText} 把命令交权限引擎破坏性扫描。
 * ChatService 的审批回调再按 LocalCommandClassifier 做 ALLOW/CONFIRM/DENY 分级。
 * cwd 锁死会话绑定的工作区根。
 */
public final class RunLocalAsyncTool implements Tool {

    private final WorkspaceStore workspaces;
    private final LocalJobManager jobs;

    public RunLocalAsyncTool(WorkspaceStore workspaces, LocalJobManager jobs) {
        this.workspaces = workspaces;
        this.jobs = jobs;
    }

    @Override
    public String name() { return "run_local_async"; }

    @Override
    public ToolCategory category() { return ToolCategory.EXECUTE; }

    @Override
    public String description() {
        return "在你的电脑上、当前项目（工作区）目录内【异步】执行一条命令（安装依赖、构建打包等"
                + "可能超过 2 分钟的操作），立即返回 job_id，随后必须用 local_job_poll 轮询输出与"
                + "退出码（支持 wait_seconds 长轮询），直到 DONE/FAILED。命令只会在已选择的项目"
                + "目录内执行；安全的构建命令会自动运行，危险命令会先弹窗确认。"
                + "单任务最长 60 分钟，结束结果保留 30 分钟。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("command", JsonSchema.string().description("要执行的命令（如 npm run build）"))
                .property("purpose",
                        JsonSchema.string().description("这条命令的用途（大白话，如：打包前端项目）；需要确认时展示给用户"))
                .property("tag", JsonSchema.string().description("可选短标签（install/build），轮询时回显"))
                .required("command", "purpose")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "run_local_async — 在项目目录内异步执行本机命令（install/build），"
                + "立即返回 job_id，再用 local_job_poll 长轮询。";
    }

    @Override
    public String promptGuidelines() {
        return """
            - 只在当前选择的项目目录内执行命令；先用 read_local_file 看项目文件决定命令。
            - 启动后立刻调用 local_job_poll（wait_seconds=20）；RUNNING 继续轮询，不要重复启动。
            - 构建失败：用 poll 读输出尾部定位原因；需要改代码时经 edit_local_file（会请用户确认），再重跑。
            - 命令必须带 purpose（大白话用途）；禁止把多条无关命令拼在一起。""";
    }

    @Override
    public String resolveCommandText(JsonNode input) {
        return input.path("command").asText("");
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) throws Exception {
        String command = input.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
        WorkspaceStore.Workspace ws = workspaces.boundWorkspace(ctx.sessionId());
        if (ws == null) {
            throw new IllegalStateException("尚未选择项目（工作区）：请先在工作区选择器中选择一个项目目录");
        }
        JsonNode tagNode = input.path("tag");
        String tag = tagNode.isTextual() && !tagNode.asText().isBlank() ? tagNode.asText() : null;

        String jobId = jobs.start(command, Path.of(ws.canonicalPath()), tag);

        StringBuilder sb = new StringBuilder();
        sb.append("[async_job=started]\n");
        sb.append("job_id=").append(jobId).append('\n');
        if (tag != null) sb.append("tag=").append(tag).append('\n');
        sb.append("cwd=").append(ws.canonicalPath()).append('\n');
        sb.append("command=").append(command).append('\n');
        sb.append("任务已在后台运行（最长 60 分钟，结束后结果保留 30 分钟）。\n");
        sb.append("下一步：调用 local_job_poll（job_id=").append(jobId)
          .append(", wait_seconds=20）等待结果；RUNNING 继续轮询，DONE/FAILED 后停止。");
        return sb.toString();
    }
}
