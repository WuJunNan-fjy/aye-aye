package com.aye.server.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;

import java.util.ArrayList;
import java.util.List;

/**
 * todo_write 工具 —— 会话内待办/进度管理（对标 opencode TodoWriteTool）。
 *
 * <p>用于多步任务（部署、排查、迁移）把计划拆成可跟踪的待办清单，
 * 每完成一步就更新状态，避免长任务丢步骤。归类为 READ：纯会话内规划状态、
 * 不触碰主机，因此只读执行模式下也必须允许使用。
 */
public final class TodoWriteTool implements Tool {

    private static final List<String> ACTIONS = List.of("replace", "list", "clear");
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed", "cancelled");

    private final TodoStore store;

    public TodoWriteTool(TodoStore store) {
        this.store = store;
    }

    @Override
    public String name() { return "todo_write"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "管理当前会话的待办任务列表（计划/进度跟踪）。多步任务先用它列出步骤，"
                + "每完成一步用 replace 更新状态。三种动作：replace 整体替换列表、"
                + "list 查看当前列表、clear 清空列表。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("action", JsonSchema.string()
                        .description("操作：replace 整体替换待办列表（推荐，每次重发完整列表）；"
                                + "list 返回当前列表；clear 清空")
                        .enumValues(ACTIONS.toArray(new String[0])))
                .property("items", JsonSchema.array(
                                JsonSchema.object()
                                        .property("content", JsonSchema.string().description("待办内容"))
                                        .property("status", JsonSchema.string()
                                                .description("状态：pending / in_progress / completed / cancelled，默认 pending")
                                                .enumValues(STATUSES.toArray(new String[0])))
                                        .required("content"))
                        .description("replace 时必填的待办列表；其余动作忽略"))
                .required("action")
                .build();
    }

    @Override
    public long timeoutMs() { return 10_000L; }

    @Override
    public String promptSnippet() {
        return "todo_write — 会话待办/进度管理（多步任务先列清单，逐步更新状态）。";
    }

    @Override
    public String promptGuidelines() {
        return """
                ### todo_write
                - 接到 3 步以上的任务（部署/排查/迁移/批量改动）时，先 todo_write 建清单再动手；一两步能完成的简单任务不必建。
                - 每完成或变更一步，立刻用 replace 整体重发最新列表（保持状态准确）。
                - 同一时间只保留一个 in_progress。
                - 只有拿到验证证据（命令退出码 0、复查输出、状态查询结果）后，才允许把待办标为 completed；验证失败就保持未完成并继续处理。
                - 任务被阻塞（缺权限/等用户/主机不可达）时，新增一条说明阻塞原因的待办，而不是静默跳过。
                - 所有待办完成后再收尾总结。""";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        String action = input.path("action").asText("");
        String sessionId = ctx.effectiveSessionId();

        return switch (action) {
            case "list" -> formatList(sessionId, "当前待办 " + store.list(sessionId).size() + " 项：");
            case "clear" -> {
                store.clear(sessionId);
                yield "[todo_write] 已清空待办列表。";
            }
            case "replace" -> {
                List<TodoStore.Todo> todos = parseItems(input.path("items"));
                if (todos.isEmpty()) {
                    // 参数错误走硬错误通道，避免模型把"没更新成功"当成已更新
                    throw new IllegalArgumentException("replace 需要至少一个有效待办项（items.content 不能为空）。");
                }
                store.replace(sessionId, todos);
                yield formatList(sessionId, "待办已更新，共 " + todos.size() + " 项：");
            }
            default -> throw new IllegalArgumentException(
                    "无效的 action：" + action + "，可选：" + ACTIONS);
        };
    }

    private List<TodoStore.Todo> parseItems(JsonNode items) {
        List<TodoStore.Todo> out = new ArrayList<>();
        if (!items.isArray()) return out;
        for (JsonNode it : items) {
            String content = it.path("content").asText("").trim();
            if (content.isEmpty()) continue;
            String status = it.path("status").asText("pending");
            if (!STATUSES.contains(status)) status = "pending";
            out.add(new TodoStore.Todo(TodoStore.newId(), content, status));
        }
        return out;
    }

    private String formatList(String sessionId, String header) {
        List<TodoStore.Todo> todos = store.list(sessionId);
        if (todos.isEmpty()) {
            return "[todo_write] " + header + "\n（空）";
        }
        StringBuilder sb = new StringBuilder("[todo_write] ").append(header).append('\n');
        for (int i = 0; i < todos.size(); i++) {
            TodoStore.Todo t = todos.get(i);
            String mark = switch (t.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[→]";
                case "cancelled" -> "[-]";
                default -> "[ ]";
            };
            sb.append("  ").append(mark).append(' ').append(t.content())
                    .append("  `").append(t.id()).append("`");
            if (i < todos.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
