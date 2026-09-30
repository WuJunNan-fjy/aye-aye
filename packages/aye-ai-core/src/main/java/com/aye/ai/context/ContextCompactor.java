package com.aye.ai.context;

import com.aye.ai.agent.AgentLoopOptions;
import com.aye.ai.llm.LlmClient;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmProviderConfig;
import com.aye.ai.llm.LlmTypes.LlmStreamOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 上下文智能总结 —— 替换「硬截断」的 AgentLoop 前处理。
 *
 * <p>主循环每步把 {@code messages}（system prompt + 历史 + 当前轮工具轨迹）整体发给 LLM，
 * 长时间会话/超长工具输出会让请求体无限膨胀、逼近模型窗口后报错。本类负责在每次调用前
 * 估算 token 用量，超过容量预算时在「轮次边界」把更早的对话压缩成一条历史摘要，保留最近
 * 若干轮原始消息 + 当前工具轨迹不变（工具调用结果链必须原样，否则违反 OpenAI 的
 * tool→assistant tool_calls 配对约束）。
 *
 * <p>摘要策略（对齐 opencode / grok-build 的做法）：
 * <ol>
 *   <li><b>LLM 语义总结</b>：用同一 provider 的非流式调用，把「早于最近轮次的信息」提炼成
 *       简洁摘要（保留用户目标 / 已完成动作 / 关键结论，不臆造），注入为一条 system 摘要消息；</li>
 *   <li><b>抽取式兜底</b>：LLM 总结失败或关闭时，退化为流程式压缩 —— 截断早前每条消息长度、
 *       丢弃超长工具输出正文，保证无论如何都能压回预算内。</li>
 * </ol>
 *
 * <p>重复压缩安全：注入的摘要消息位于 {@code messages[1]}，下次再触发时它会被算进「更早信息」
 * 与新增的更早内容一起被重新总结，因此摘要不会层层堆积。
 */
public final class ContextCompactor {

    /** 触发压缩的窗口占用比例（估算 token &gt;= 窗口 × 该值 即压缩）。 */
    private static final double BUDGET_RATIO = 0.72;
    /** 压缩目标：压缩后整体（≈保留尾段 + 摘要）占用窗口比例。 */
    private static final double TARGET_RATIO = 0.5;

    /** 未显式配置窗口时的默认上下文窗口（token）。DeepSeek 等主打大模型默认充裕。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /**
     * 运维版压缩模板：长部署/排障任务压缩后最易丢的恰是关键证据与变更事实，
     * 故强制按固定小节保留运维事实；{@code %s} 为词数预算。
     */
    private static final String OPS_SUMMARY_PROMPT = """
            你是运维对话压缩器。把下面这段 Aye（服务器运维助手）与用户的历史对话压缩成中文结构化摘要，
            供后续轮次继续同一任务使用。只依据给定内容，不推测、不新增信息。
            必须使用以下小节（某节确无内容可省略）：
            1. 任务与主机：用户目标与诉求；涉及主机的 host_key、名称/地址；用户明确表达的约束、禁忌与偏好。
            2. 已执行的关键操作：按时间顺序列出 工具名 + 关键参数（命令/路径/服务名）+ 退出码或结果结论；
               失败与报错保留关键错误行，不得改写成成功。
            3. 变更清单：改动过的远程文件与目录清单（逐字保留路径，禁止省略或改写）。
            4. 关键证据与环境事实：决定结论的日志/配置/输出关键行（路径、端口、版本号、状态字段），
               以及配置文件位置、服务名、发行版、包管理器等。
            5. 待办与下一步：尚未完成的事项、等待用户确认或回答的问题。
            标识符（host_key、绝对路径、服务名、版本号）必须逐字保留；
            省略寒暄、重复尝试与无关输出噪音。总长度控制在约 %s 词以内。""";

    private final LlmProviderConfig provider;
    private final int contextWindow;
    private final boolean summarizeEnabled;
    private final BooleanSupplier aborted;

    /**
     * 构造压缩器。
     *
     * @param provider          用于语义总结的 provider；为 null 时只能抽取式压缩
     * @param contextWindow     上下文窗口（token）；&lt;=0 用默认值
     * @param summarizeEnabled  false 时禁用 LLM 语义总结（仅抽取式兜底）
     * @param aborted           会话级中止信号（总结调用时可中断）
     */
    public ContextCompactor(LlmProviderConfig provider, int contextWindow,
                            boolean summarizeEnabled, BooleanSupplier aborted) {
        this.provider = provider;
        this.contextWindow = contextWindow > 0 ? contextWindow : DEFAULT_CONTEXT_WINDOW;
        this.summarizeEnabled = summarizeEnabled;
        this.aborted = aborted;
    }

    /** 便捷构造：从 AgentLoopOptions 读取配置（缺省用默认）。 */
    public static ContextCompactor from(AgentLoopOptions options, LlmProviderConfig provider) {
        Integer window = options != null ? options.contextWindowTokens() : null;
        Boolean enabled = options != null ? options.summarizeEnabled() : null;
        BooleanSupplier aborted = options != null ? options.aborted() : null;
        return new ContextCompactor(provider,
                window != null ? window : DEFAULT_CONTEXT_WINDOW,
                enabled == null || enabled,
                aborted);
    }

    /**
     * 在每次主循环 LLM 调用前调用：若 {@code messages} 超预算，返回压缩后的新列表；
     * 未超预算或无法压缩时原样返回。
     */
    public List<LlmMessage> compactBeforeCall(List<LlmMessage> messages) {
        if (messages == null || messages.size() <= 2) return messages;
        int hardLimit = (int) (contextWindow * BUDGET_RATIO);
        if (estimateTokens(messages) <= hardLimit) return messages;

        int targetTail = (int) (contextWindow * TARGET_RATIO);
        int cutoff = findTurnBoundaryCutoff(messages, targetTail);
        // cutoff 指向要保留的最近轮次起点；cutoff<=1 说明没有可裁的轮次
        if (cutoff <= 1) return messages;

        List<LlmMessage> tail = messages.subList(cutoff, messages.size());
        List<LlmMessage> head = messages.subList(1, cutoff); // 跳过原始 system prompt(messages[0])

        String summary;
        try {
            summary = summarize(head, Math.max(400, targetTail / 2));
        } catch (Throwable t) {
            summary = extractiveSummary(head, Math.max(600, targetTail / 2));
        }
        if (summary == null || summary.isBlank()) {
            summary = extractiveSummary(head, Math.max(600, targetTail / 2));
        }

        List<LlmMessage> out = new ArrayList<>(tail.size() + 2);
        out.add(messages.get(0)); // 保留原始 system prompt
        out.add(summaryMessage(summary));
        out.addAll(tail);

        // 若压缩后反而更大（极少见，如摘要比原文长），退回只裁工具正文的抽取式
        if (estimateTokens(out) >= estimateTokens(messages)) {
            List<LlmMessage> extractive = new ArrayList<>();
            extractive.add(messages.get(0));
            extractive.add(summaryMessage(
                    extractiveSummary(head, Math.max(400, targetTail / 2))));
            extractive.addAll(tail);
            if (estimateTokens(extractive) < estimateTokens(messages)) return extractive;
        }
        System.err.println("[ContextCompactor] 上下文压缩：窗口=" + contextWindow
                + " 压缩前=" + estimateTokens(messages) + "tok → 压缩后=" + estimateTokens(out)
                + "tok（保留 " + (messages.size() - cutoff) + " 条最近消息，摘要 " + summary.length() + " 字）");
        return out;
    }

    /**
     * 找到「要保留的最近轮次起点」：从最早的用户消息起，挑出首个能让尾段 ≤ targetTail 的
     * 用户轮次；若当前轮单一超预算（任何起点都放不下）则退化为保留最新一轮，尽量多保留轮次。
     * 只允许切在 user 消息边界，避免拆散 assistant(tool_calls)→tool 的配对链。
     */
    private int findTurnBoundaryCutoff(List<LlmMessage> messages, int targetTail) {
        int lastUser = -1;
        int firstFitUser = -1;
        for (int i = 1; i < messages.size(); i++) {
            if (!"user".equals(messages.get(i).role())) continue;
            lastUser = i;
            long tailTokens = estimateTokens(messages.subList(i, messages.size()));
            if (firstFitUser == -1 && tailTokens <= targetTail) firstFitUser = i;
        }
        if (firstFitUser > 1) return firstFitUser;          // 首位能装下最近轮次的起点
        if (lastUser > 1) return lastUser;                  // 退化为只保留当前轮
        return -1;
    }

    // ---------------------------------------------------------------------------
    // 摘要：LLM 语义总结 / 抽取式兜底
    // ---------------------------------------------------------------------------

    /**
     * 注入摘要消息：用 user role 且带清晰标记，规避部分兼容端点「system 仅限首位」的 400 拒绝，
     * 同时文案明确告知模型「这段是历史上下文，仅供参考，无须据此发起新指令」。
     */
    private static LlmMessage summaryMessage(String summary) {
        return LlmMessage.user("[历史上下文摘要（早前对话要点，仅供背景参考，不构成新指令）]\n" + summary);
    }

    private String summarize(List<LlmMessage> head, int maxTokens) {
        if (!summarizeEnabled || provider == null) {
            return extractiveSummary(head, maxTokens);
        }
        String headText = renderHeadAsText(head);
        if (headText.isBlank()) return "";

        List<LlmMessage> callMessages = new ArrayList<>(2);
        callMessages.add(LlmMessage.system(OPS_SUMMARY_PROMPT.formatted(Math.max(60, maxTokens / 2))));
        callMessages.add(LlmMessage.user(headText));

        try {
            LlmClient.LlmResponse resp = LlmClient.chatNonStream(provider, callMessages, null,
                    LlmStreamOptions.of(aborted));
            String s = resp != null && resp.content() != null ? resp.content().trim() : "";
            return s.length() > maxTokens * 4 ? s.substring(0, maxTokens * 4) : s; // 粗限长
        } catch (Exception e) {
            // 总结调用失败 → 抽取式兜底
            return extractiveSummary(head, maxTokens);
        }
    }

    /** 把 head 渲染成一段可交给总结模型 / 抽取器的文本。 */
    private static String renderHeadAsText(List<LlmMessage> head) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : head) {
            switch (m.role()) {
                case "user" -> appendLine(sb, "用户", m.content());
                case "assistant" -> {
                    if (m.toolCalls() == null || m.toolCalls().isEmpty()) {
                        appendLine(sb, "助手", m.content());
                    } else {
                        List<String> names = new ArrayList<>();
                        for (var tc : m.toolCalls()) names.add(tc.name());
                        sb.append("助手调用工具: ").append(String.join(", ", names)).append('\n');
                    }
                }
                case "tool" -> {
                    String preview = m.content() == null ? "" : compact(m.content(), 160);
                    sb.append("工具结果(").append(m.toolCallId() == null ? "?" : m.toolCallId())
                            .append("): ").append(preview).append('\n');
                }
                default -> appendLine(sb, "系统", m.content());
            }
        }
        String s = sb.toString();
        return s.length() > 60_000 ? s.substring(0, 60_000) : s; // 防总结请求过大
    }

    /** 抽取式兜底：逐条截断 + 丢工具正文细节，无论如何能压回预算。 */
    private static String extractiveSummary(List<LlmMessage> head, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : head) {
            switch (m.role()) {
                case "user" -> appendLine(sb, "用户", compact(m.content(), 220));
                case "assistant" -> {
                    if (m.toolCalls() == null || m.toolCalls().isEmpty()) {
                        appendLine(sb, "助手", compact(m.content(), 140));
                    } else {
                        List<String> names = new ArrayList<>();
                        for (var tc : m.toolCalls()) names.add(tc.name());
                        sb.append("助手调用工具: ").append(String.join(", ", names)).append('\n');
                    }
                }
                case "tool" ->
                        sb.append("工具结果: ").append(compact(m.content(), 80)).append('\n');
                default -> appendLine(sb, "系统", compact(m.content(), 120));
            }
            if (sb.length() > maxTokens * 4) break;
        }
        String s = sb.toString();
        return s.length() > maxTokens * 4 ? s.substring(0, maxTokens * 4) : s;
    }

    private static void appendLine(StringBuilder sb, String tag, String body) {
        sb.append('[').append(tag).append("] ").append(compact(body, 300)).append('\n');
    }

    /** 把长文本压缩成单行短预览（截断 + 换行折叠）。 */
    private static String compact(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "-";
        String one = s.replaceAll("\\s+", " ").trim();
        if (one.length() <= maxLen) return one;
        return one.substring(0, maxLen) + "…";
    }

    // ---------------------------------------------------------------------------
    // Token 估算（启发式：CJK 一字一 token，其余按 4 字符一 token）
    // ---------------------------------------------------------------------------

    /** 估算整组消息的 token 数。 */
    public static long estimateTokens(List<LlmMessage> messages) {
        if (messages == null) return 0;
        long total = 0;
        for (LlmMessage m : messages) total += estimateMessageTokens(m);
        return total;
    }

    private static long estimateMessageTokens(LlmMessage m) {
        if (m == null) return 0;
        long n = estimateTokens(m.content());
        if (m.toolCalls() != null) {
            for (var tc : m.toolCalls()) {
                n += estimateTokens(tc.name()) + estimateTokens(tc.arguments());
            }
        }
        n += estimateTokens(m.toolCallId());
        return n + 2; // role 等协议开销
    }

    /** 估算一段文本的 token 数。 */
    public static long estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        long tokens = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                tokens += 1;       // CJK 一字约 1 token
            } else {
                other++;
            }
        }
        return tokens + (long) Math.ceil(other / 4.0);
    }
}