package com.aye.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.tool.ToolContext.AskUserCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ask_user — Agent 主动向用户提问的工具（对应 DeepWork 的 {@code ask_user.ts}）。
 *
 * <p>当 Agent 在执行任务过程中需要补充信息、确认偏好、或遇到无法自行判断的分叉点时，
 * 调用本工具暂停主循环、向前端推一条 ask_user 事件，阻断等用户作答后再继续。
 *
 * <p>本质是「读」类交互工具：不产生副作用，天然不需要人工审批，故 always-load 直接暴露给 LLM。
 * 回传经由 {@link ChatService} 注入的 {@link AskUserCallback}（挂到 {@link PendingInteractionHub}），
 * 未被注入时（脱离 Server 单独跑 AI 核心）返回错误提示让 LLM 收敛，避免死等。
 */
public final class AskUserTool implements Tool {

    public static final AskUserTool INSTANCE = new AskUserTool();

    private AskUserTool() {}

    @Override
    public String name() { return "ask_user"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    /** 会阻塞等待用户作答，不能与其它工具同批并行（否则确认/询问通道交错） */
    @Override
    public boolean isConcurrencySafe() { return false; }

    /**
     * 等待上限必须大于交互中心的挂起超时（PendingInteractionHub#INTERACTION_TIMEOUT_MS=10min）：
     * 由 hub 在 10 分钟无决议时返回 timedOut 闭合，工具线程随之正常释放；
     * 框架默认的 30s 工具超时会提前把人工等待误杀为「超时跳过」（AC-5）。
     */
    @Override
    public long timeoutMs() { return 11L * 60L * 1000L; }

    @Override
    public String description() {
        return "主动向用户提问。当任务需要用户补充信息、确认偏好或选择方向时调用，会暂停等待用户回答后再继续。"
                + "回答结果会作为工具输出返回给你。仅在确有必要（信息缺失、二选一决策）时使用，可选项一目了然时优先自行判断不要打扰用户。"
                + "能用只读工具自行查到的信息（主机状态、文件内容、日志、进程是否存在等）先查再问，不要把可自查的问题抛给用户。"
                + "当问题存在 2-4 个明确分叉时，用 options 给出短选项（前端会渲染成可点按钮，并始终附带「其他」让用户自定义作答）；"
                + "纯开放问题不要硬凑选项。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("question", JsonSchema.string()
                        .description("面向用户的具体提问。用词清晰，给出足够的上下文，让用户不用回想就能回答。")
                        .minLength(1)
                        .maxLength(500))
                .property("options", JsonSchema.array(JsonSchema.string()
                                .maxLength(100))
                        .description("可选。2-4 个互斥的短选项（每项不超过 100 字），用于让用户点选；"
                                + "前端会自动追加「其他」供自定义回答。纯开放问题不要传。")
                        .minItems(2)
                        .maxItems(4))
                .property("hint", JsonSchema.string()
                        .description("可选。补充说明 / 示例，帮助用户快速作答。给了 options 时通常不需要。")
                        .maxLength(300))
                .required("question")
                .build();
    }

    @Override
    public String promptSnippet() {
        return "任务需要用户判断或补充信息时，可以调用 ask_user 向用户提问并等待回答。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        AskUserCallback cb = ctx.askUser();
        if (cb == null) {
            return "ERROR: ask_user 未接入会话交互通道（AskUserCallback 未注入）。"
                    + "若非运行在 Aye 会话中，请直接给出你对问题的最佳假设并继续，不要卡住。";
        }
        String question = input.has("question") ? input.get("question").asText() : "";
        String hint = input.has("hint") ? input.get("hint").asText() : null;
        List<String> options = new ArrayList<>();
        if (input.has("options") && input.get("options").isArray()) {
            for (JsonNode opt : input.get("options")) {
                String text = opt.asText("").trim();
                if (!text.isEmpty() && !options.contains(text)) options.add(text);
            }
        }
        String askId = "ask-" + UUID.randomUUID();
        try {
            String answer = cb.askUser(askId, question, hint, options.isEmpty() ? null : options);
            if (answer == null || answer.isBlank()) {
                return "[ask_user] 用户跳过了该问题，没有作答。请基于现有信息自行决定并继续。";
            }
            return "[ask_user] 用户回答:\n" + answer;
        } catch (Exception e) {
            return "ERROR: 询问用户失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}