package com.aye.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

/**
 * deploy_stage 工具 —— 声明部署进入哪个阶段、该阶段状态与小结。
 *
 * <p>READ 类（账本记账，不改系统）、免确认。部署账本由此驱动，
 * 前端阶段卡片从账本投影。阶段可回退（构建失败修复后可重新声明 build/running）。
 */
public final class DeployStageTool implements Tool {

    private static final int SUMMARY_MAX = 500;

    private final DeployRunStore runs;
    private final WorkspaceStore workspaces;

    public DeployStageTool(DeployRunStore runs, WorkspaceStore workspaces) {
        this.runs = runs;
        this.workspaces = workspaces;
    }

    @Override
    public String name() { return "deploy_stage"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "声明部署进度：进入某阶段（select/detect/prepare/build/confirm/upload/verify）"
                + "及其状态（running/done/failed/skipped）与大白话小结。每完成一步都应调用，"
                + "用户界面上的进度据此更新。阶段失败时声明 failed 并说明原因。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("stage", JsonSchema.string()
                        .description("阶段：select/detect/prepare/build/confirm/upload/verify"))
                .property("status", JsonSchema.string()
                        .description("状态：running/done/failed/skipped"))
                .property("summary", JsonSchema.string()
                        .description("面向用户的小结（大白话，≤500 字）"))
                .property("command", JsonSchema.string().description("可选：该阶段执行的关键命令"))
                .property("purpose", JsonSchema.string().description("可选：命令用途"))
                .required("stage", "status")
                .build();
    }

    @Override
    public long timeoutMs() { return 10_000L; }

    @Override
    public String promptSnippet() {
        return "deploy_stage — 声明部署阶段与状态，驱动进度卡片。";
    }

    @Override
    public String promptGuidelines() {
        return """
            - 每一步开始时声明 running，完成时声明 done + 大白话小结；失败声明 failed + 原因。
            - 阶段②(detect)完成后用一条小结告诉用户这是什么项目；阶段⑤(confirm)声明后等待用户确认。
            - 修复构建问题后可重新声明 build；不要跳着声明未实际完成的阶段。""";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        DeployStage stage = DeployStage.byId(input.path("stage").asText(""));
        String status = input.path("status").asText("");
        if (stage == null) {
            throw new IllegalArgumentException("stage 非法（应为七阶段 id 之一）");
        }
        if (!status.equals("running") && !status.equals("done")
                && !status.equals("failed") && !status.equals("skipped")) {
            throw new IllegalArgumentException("status 非法（running/done/failed/skipped）");
        }
        WorkspaceStore.Workspace ws = workspaces.boundWorkspace(ctx.sessionId());
        if (ws == null) {
            throw new IllegalStateException("尚未选择项目（工作区），无法记录部署进度");
        }
        String summary = clip(input.path("summary").asText(""));
        String command = nullIfBlank(input.path("command").asText(""));
        String purpose = nullIfBlank(input.path("purpose").asText(""));

        // hostId 未知时以 0 占位（上传/远程阶段会带 host_key 的工具各自解析）。
        DeployRunStore.DeployRun run =
                runs.createOrGet(ws.id(), ctx.sessionId(), 0);
        runs.updateStage(run.id(), stage,
                new StageState(status, command, purpose, summary, System.currentTimeMillis()));

        return "[stage=" + stage.id() + " " + status + "]\n"
                + "项目：" + ws.title() + "\n"
                + (summary.isEmpty() ? "" : "小结：" + summary + "\n")
                + "进度已更新。";
    }

    private static String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) : s;
    }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
