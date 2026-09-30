package com.aye.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.context.ContextCompactor;
import com.aye.ai.event.AgentEvent.DoneEvent;
import com.aye.ai.event.AgentEvent.ErrorEvent;
import com.aye.ai.event.AgentEvent.TextDeltaEvent;
import com.aye.ai.event.AgentEvent.ThinkingEvent;
import com.aye.ai.event.AgentEvent.ToolCallEvent;
import com.aye.ai.event.AgentEvent.ToolResultEvent;
import com.aye.ai.llm.LlmClient;
import com.aye.ai.llm.LlmTypes.LlmEvent;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmProviderConfig;
import com.aye.ai.llm.LlmTypes.LlmStreamOptions;
import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.llm.LlmTypes.LlmToolDefinition;
import com.aye.ai.llm.LlmTypes.UsageStats;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessageToLlm;
import com.aye.ai.permission.DestructiveTag;
import com.aye.ai.prompt.SystemPromptBuilder;
import com.aye.ai.session.RunCoordinates;
import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventLog;
import com.aye.ai.session.SessionEventType;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.tool.ToolExecutor;
import com.aye.ai.tool.ToolRegistry;
import com.aye.ai.tool.ToolResult;
import com.aye.ai.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Agent Loop 核心循环（完全自研 LLM 客户端版，对应 DeepWork 的 {@code agent/loop.ts}）。
 *
 * <p>一次 while 循环：UIMessage→LLMMessage 转换 + system prompt 注入 → chatStream 消费事件 →
 * TruncationHandler 截断 4 层防御 → 按 nextAction 分发 → 执行工具 / 结束。
 *
 * <p>双写过渡期：旧 {@link AgentEventSink}（SSE 逐 token 推送）保持不变；当
 * {@link AgentLoopOptions#eventLog()} 非空时，同时把 dsh 事件词汇表事实提交到
 * {@link SessionEventLog}（唯一持久事实源），边界由编排层负责 turn/start，
 * 本类保证每个退出路径都恰好闭合一次 turn/end。
 */
public final class AgentLoop {

    private AgentLoop() {
    }

    // ---------------------------------------------------------------------------
    // 公共 API：流式
    // ---------------------------------------------------------------------------
    public static void agentLoop(List<UIMessage> initialMessages, LlmProviderConfig providerConfig,
                                 ToolRegistry toolRegistry, AgentLoopOptions options,
                                 AgentEventSink sink) throws Exception {
        int maxSteps = options != null && options.maxSteps() != null
                ? options.maxSteps() : AgentConstants.DEFAULT_MAX_STEPS;
        BooleanSupplier aborted = options != null ? options.aborted() : null;
        ToolContext toolContext = options != null && options.toolContext() != null
                ? options.toolContext() : new ToolContext();

        String systemPrompt = SystemPromptBuilder.buildSystemPrompt(
                toolRegistry,
                toolContext.effectiveCwd(),
                options != null ? options.systemSupplement() : null,
                toolContext.executionMode(),
                options != null ? options.envSupplement() : null);

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.addAll(UIMessageToLlm.convert(initialMessages));

        int initialMaxOutput = options != null && options.maxOutputTokens() != null
                ? options.maxOutputTokens() : AgentConstants.DEFAULT_MAX_OUTPUT_TOKENS;
        TruncationHandler truncation = new TruncationHandler(initialMaxOutput);
        // 上下文智能总结：每次主循环调用前估算 token，超预算时压缩早前对话成摘要（替换硬截断）
        ContextCompactor compactor = ContextCompactor.from(options, providerConfig);
        int step = 0;

        // ---- 事件溯源接线（eventLog 非空时双写；null 时整体退化为旧行为） ----
        final SessionEventLog log = options != null ? options.eventLog() : null;
        final int turn = options != null && options.turnNo() != null ? options.turnNo() : 1;
        final String mode = options != null ? options.mode() : null;
        final String modelId = providerConfig.model();
        final RunCoordinates coords = new RunCoordinates().turnNo(turn);
        final TurnExit exit = new TurnExit();
        if (log != null) {
            toolContext.runCoordinates(coords);
            installInteractionWrappers(toolContext, log, coords, turn);
            toolContext.autoDecisionListener((callId, decision, m, rule) -> {
                try {
                    log.toolDecided(coords.turnNo(), coords.stepNo(), callId, decision, "auto", m, rule);
                } catch (Exception ignored) {
                    // 留痕失败不反噬工具执行
                }
            });
            // 首次 LLM 请求头快照（system prompt + 全量工具目录）；收尾轮另发一份空工具头
            log.requestHeader(turn, SessionEvent.NO_COORD, "start", modelId, mode,
                    systemPrompt, toolRegistry.list().stream().map(t -> t.name()).toList());
        }
        final Consumer<String> injectSink = log == null ? null : text -> {
            try {
                log.userMessage(turn, text, SessionEventType.SOURCE_INJECT);
            } catch (Exception ignored) {
                // 注入事实留痕失败不影响主循环
            }
        };

        // 死循环检测状态：上次工具调用指纹 + 连续重复次数 + 是否已注入过警示
        String lastFingerprint = null;
        int consecutiveRepeats = 0;
        boolean loopWarningInjected = false;

        // 软收尾：工作步（1..maxSteps）全部用完后，追加且仅追加一轮「收尾轮」——
        // 注入强制总结指令且不下发任何工具定义（模型无法发起 tool_call），
        // 复用现有流式路径自然走到 DoneEvent，替代原先撞顶即 ErrorEvent 的硬终止。
        boolean wrapUp = false;
        boolean wrapHeaderPending = false;
        try {
            while (true) {
                if (isAborted(aborted)) {
                    exit.reason = SessionEventType.REASON_ABORTED;
                    sink.accept(new ErrorEvent("用户取消请求"));
                    return;
                }
                // 步边界 drain 中途插话：协作式注入，只在 LLM 调用前生效，
                // 不打断进行中的流式输出 / 工具执行（工具批结束后自然走到这里）。
                drainInterjections(options, messages, injectSink);
                step++;
                coords.stepNo(step);
                if (log != null) {
                    if (wrapHeaderPending) {
                        log.requestHeader(turn, step, "wrap-up", modelId, mode, systemPrompt, List.of());
                        wrapHeaderPending = false;
                    }
                    log.stepStarted(turn, step);
                }
                final int currentStep = step;
                try {
                    // 收尾轮不下发任何工具定义（收尾指令在转入时注入一次，见循环尾部）
                    List<LlmToolDefinition> toolDefs = wrapUp
                            ? null : toolRegistry.toToolDefinitions();

                    // ---------- 步骤 2：chatStream + 消费事件 ----------
                    String[] assistantContent = {""};
                    @SuppressWarnings("unchecked")
                    List<LlmToolCall>[] toolCalls = new List[]{null};
                    String[] stopReason = {null};
                    boolean[] streamSucceeded = {false};
                    UsageStats[] streamUsage = {null};
                    boolean[] compactedBox = {false};

                    // 事件流 chunk 组装状态：blockIndex 按流式段类型切换递增
                    final String[] chunkKind = {null};
                    final int[] blockIndex = {-1};
                    final StringBuilder reasoningBuf = new StringBuilder();
                    final List<Integer> chunkSeqs = new ArrayList<>();

                    try {
                        List<LlmMessage> beforeCompact = messages;
                        messages = compactor.compactBeforeCall(messages);
                        compactedBox[0] = (beforeCompact != messages);
                        if (log != null && compactedBox[0] && messages.size() >= 2) {
                            // 压缩器把摘要注入为 messages[1] 的 system 消息（见 ContextCompactor 契约）
                            log.compactionSummary(turn, currentStep, messages.get(1).content(), -1, -1);
                        }
                        LlmStreamOptions streamOptions = new LlmStreamOptions(
                                aborted, options != null ? options.temperature() : null,
                                truncation.currentMaxOutputTokens());
                        LlmClient.chatStream(providerConfig, messages, toolDefs, streamOptions, ev -> {
                            if (ev instanceof LlmEvent.Thinking t) {
                                sink.accept(new ThinkingEvent(t.content()));
                                if (log != null) {
                                    if (!"reasoning".equals(chunkKind[0])) {
                                        blockIndex[0]++;
                                        chunkKind[0] = "reasoning";
                                    }
                                    reasoningBuf.append(t.content());
                                    ObjectNode c = Json.object()
                                            .put("chunkType", "reasoning-delta")
                                            .put("blockIndex", blockIndex[0])
                                            .put("text", t.content());
                                    chunkSeqs.add(log.chunk(turn, currentStep, c).seq());
                                }
                            } else if (ev instanceof LlmEvent.Text t) {
                                assistantContent[0] += t.content();
                                sink.accept(new TextDeltaEvent(t.content()));
                                if (log != null) {
                                    if (!"text".equals(chunkKind[0])) {
                                        blockIndex[0]++;
                                        chunkKind[0] = "text";
                                    }
                                    ObjectNode c = Json.object()
                                            .put("chunkType", "text-delta")
                                            .put("blockIndex", blockIndex[0])
                                            .put("text", t.content());
                                    chunkSeqs.add(log.chunk(turn, currentStep, c).seq());
                                }
                            } else if (ev instanceof LlmEvent.ToolCall tc) {
                                sink.accept(new ToolCallEvent(tc.id(), tc.toolName(), parseOrText(tc.arguments())));
                            } else if (ev instanceof LlmEvent.Done d) {
                                streamUsage[0] = d.usage();
                                if (d.content().length() > assistantContent[0].length()) {
                                    String missing = d.content().substring(assistantContent[0].length());
                                    assistantContent[0] = d.content();
                                    if (!missing.isEmpty()) {
                                        sink.accept(new TextDeltaEvent(missing));
                                        if (log != null) {
                                            if (!"text".equals(chunkKind[0])) {
                                                blockIndex[0]++;
                                                chunkKind[0] = "text";
                                            }
                                            ObjectNode c = Json.object()
                                                    .put("chunkType", "text-delta")
                                                    .put("blockIndex", blockIndex[0])
                                                    .put("text", missing);
                                            chunkSeqs.add(log.chunk(turn, currentStep, c).seq());
                                        }
                                    }
                                } else {
                                    assistantContent[0] = d.content();
                                }
                                toolCalls[0] = d.toolCalls();
                                stopReason[0] = d.stopReason();
                                streamSucceeded[0] = true;
                                if (log != null) {
                                    emitAssistantMessage(log, turn, currentStep, modelId,
                                            reasoningBuf, assistantContent[0], d.toolCalls(),
                                            d.usage(), chunkSeqs);
                                }
                            }
                        });
                    } catch (Exception err) {
                        // 取消信号常在 LLM HTTP in-flight 时以异常形式浮现：按 aborted 闭合，
                        // 不得记为 error（FR-2/FR-8/AC-4），也不向前端发 error 事件
                        if (isAborted(aborted)) {
                            exit.reason = SessionEventType.REASON_ABORTED;
                            // Done 可能已触发（assistant/message 已落盘），补齐悬空调用
                            emitInterruptedToolResults(log, sink, turn, currentStep, toolCalls[0]);
                            return;
                        }
                        // Done 已触发后抛错的极端情况：assistant/message 已落盘，同样补齐配对
                        emitInterruptedToolResults(log, sink, turn, currentStep, toolCalls[0]);
                        exit.reason = SessionEventType.REASON_ERROR;
                        exit.error = formatError(err);
                        sink.accept(new ErrorEvent(formatError(err)));
                        return;
                    }

                    if (!streamSucceeded[0]) {
                        if (isAborted(aborted)) {
                            exit.reason = SessionEventType.REASON_ABORTED;
                            emitInterruptedToolResults(log, sink, turn, currentStep, toolCalls[0]);
                            return;
                        }
                        exit.reason = SessionEventType.REASON_ERROR;
                        exit.error = "LLM 流式响应异常结束，未收到 [DONE] 标记";
                        sink.accept(new ErrorEvent("LLM 流式响应异常结束，未收到 [DONE] 标记"));
                        return;
                    }

                    // ---------- 步骤 3：TruncationHandler 统一处理 4 层防御 ----------
                    TruncationHandler.Result tc = truncation.processAssistantOutput(
                            assistantContent[0], toolCalls[0], stopReason[0]);
                    messages.add(tc.assistantMsg());

                    // ---------- 步骤 4：按 nextAction 分发 ----------
                    if (tc.nextAction() == TruncationHandler.NextAction.RETRY_TEXT) continue;

                    if (tc.nextAction() == TruncationHandler.NextAction.ERROR_TEXT_TOO_MANY
                            || tc.nextAction() == TruncationHandler.NextAction.ERROR_TOOL_TOO_MANY) {
                        exit.reason = SessionEventType.REASON_ERROR;
                        exit.error = tc.errorUserFacing();
                        sink.accept(new ErrorEvent(tc.errorUserFacing()));
                        return;
                    }

                    if (tc.nextAction() == TruncationHandler.NextAction.RETRY_TOOL
                            && toolCalls[0] != null && tc.toolErrorMessages() != null) {
                        for (int i = 0; i < toolCalls[0].size(); i++) {
                            LlmToolCall call = toolCalls[0].get(i);
                            messages.add(tc.toolErrorMessages().get(i));
                            sink.accept(new ToolResultEvent(call.id(), call.name(),
                                    Json.object().put("error", "输出被截断，参数不完整").put("truncated", true), true));
                            if (log != null) {
                                JsonNode argsNode = parseOrText(call.arguments());
                                log.toolCall(turn, currentStep, call.id(), call.name(), argsNode,
                                        hostKeyOf(argsNode), categoryOf(toolRegistry, call.name()));
                                log.toolResult(turn, currentStep, call.id(), call.name(),
                                        Json.object().put("error", "输出被截断，参数不完整").put("truncated", true),
                                        true, null, null, hostKeyOf(argsNode), false);
                            }
                        }
                        continue;
                    }

                    // ---------- 步骤 5：无工具调用 → 正常结束 ----------
                    if (toolCalls[0] == null || toolCalls[0].isEmpty()) {
                        exit.reason = SessionEventType.REASON_COMPLETED;
                        sink.accept(new DoneEvent());
                        return;
                    }

                    // 收尾轮理论上不可能出现工具调用（tools 未下发）；防御性处理：不执行，直接结束
                    if (wrapUp) {
                        exit.reason = SessionEventType.REASON_COMPLETED;
                        sink.accept(new DoneEvent());
                        return;
                    }

                    // ---------- 步骤 6：正常工具执行 ----------
                    if (isAborted(aborted)) {
                        exit.reason = SessionEventType.REASON_ABORTED;
                        // assistant/message（含 tool-call blocks）已落盘，工具来不及执行：
                        // 补 interrupted 结果，否则下轮历史折叠出现悬空调用 → LLM 400
                        emitInterruptedToolResults(log, sink, turn, currentStep, toolCalls[0]);
                        sink.accept(new ErrorEvent("用户取消请求"));
                        return;
                    }

                    // ---------- 步骤 6.5：死循环检测（连续 N 次同工具同输入） ----------
                    if (toolCalls[0] != null && !toolCalls[0].isEmpty()) {
                        String fp = fingerprint(toolCalls[0].get(0));
                        consecutiveRepeats = (fp != null && fp.equals(lastFingerprint)) ? consecutiveRepeats + 1 : 1;
                        lastFingerprint = fp;
                        if (consecutiveRepeats >= AgentConstants.DOOM_LOOP_THRESHOLD) {
                            if (!loopWarningInjected) {
                                // 注入一条用户侧警示，给模型一次纠错机会后继续
                                String warn = "[系统警示] 你已连续 "
                                        + AgentConstants.DOOM_LOOP_THRESHOLD
                                        + " 次以相同工具和相同参数重复执行，疑似陷入死循环。"
                                        + "请更换策略，不要重复同一操作；若任务已完成请直接输出结论。";
                                messages.add(LlmMessage.user(warn));
                                if (injectSink != null) injectSink.accept(warn);
                                loopWarningInjected = true;
                                consecutiveRepeats = 0;
                                continue;
                            }
                            exit.reason = SessionEventType.REASON_ERROR;
                            exit.error = "检测到死循环：连续多次以相同工具和相同参数执行，已提示一次仍未修正，Agent Loop 终止";
                            sink.accept(new ErrorEvent("检测到死循环：连续多次以相同工具和相同参数执行，"
                                    + "已提示一次仍未修正，Agent Loop 终止"));
                            return;
                        }
                    }

                    if (log != null) {
                        for (LlmToolCall call : toolCalls[0]) {
                            JsonNode argsNode = parseOrText(call.arguments());
                            log.toolCall(turn, currentStep, call.id(), call.name(), argsNode,
                                    hostKeyOf(argsNode), categoryOf(toolRegistry, call.name()));
                        }
                    }
                    List<ToolResult> executionResults;
                    try {
                        executionResults = ToolExecutor.executeToolCalls(toolCalls[0], toolRegistry, toolContext);
                    } catch (Exception err) {
                        // 工具执行阶段整体抛出（正常情况下所有工具异常已在执行器内吃成错误结果）：
                        // 无论中止还是错误，都先补齐未完成调用的 interrupted 结果事件
                        emitInterruptedToolResults(log, sink, turn, currentStep, toolCalls[0]);
                        // 工具执行期间取消信号生效（如 SSH 读循环被中断）按 aborted 闭合
                        if (isAborted(aborted)) {
                            exit.reason = SessionEventType.REASON_ABORTED;
                            return;
                        }
                        exit.reason = SessionEventType.REASON_ERROR;
                        exit.error = "工具执行阶段异常：" + formatError(err);
                        sink.accept(new ErrorEvent("工具执行阶段异常：" + formatError(err)));
                        return;
                    }

                    for (int k = 0; k < executionResults.size(); k++) {
                        ToolResult result = executionResults.get(k);
                        messages.add(result.toolMessage());
                        sink.accept(new ToolResultEvent(
                                result.toolMessage().toolCallId(),
                                result.toolName(),
                                Json.toNode(result.rawOutput()),
                                result.isError()));
                        if (log != null) {
                            LlmToolCall src = toolCalls[0].get(k);
                            JsonNode argsNode = parseOrText(src.arguments());
                            JsonNode outNode = result.rawOutput() == null
                                    ? NullNode.getInstance() : Json.toNode(result.rawOutput());
                            log.toolResult(turn, currentStep,
                                    result.toolMessage().toolCallId(),
                                    result.toolName(), outNode, result.isError(),
                                    null, null,
                                    hostKeyOf(argsNode), false);
                        }
                    }

                    // 工作步预算用尽 → 注入一次收尾指令，下一轮进入无工具收尾轮（软收尾）
                    if (step >= maxSteps && !wrapUp) {
                        String wrapText = "[系统] 已达最大步数上限（" + maxSteps
                                + " 步）。请立即停止调用任何工具，基于已有信息输出："
                                + "1) 最终结论或阶段性成果；2) 已完成的关键操作；"
                                + "3) 未完成事项与建议的下一步。";
                        messages.add(LlmMessage.user(wrapText));
                        if (injectSink != null) injectSink.accept(wrapText);
                        wrapUp = true;
                        // 收尾轮请求头：不下发任何工具定义
                        wrapHeaderPending = log != null;
                    }
                } finally {
                    if (log != null) {
                        try {
                            log.stepEnded(turn, currentStep);
                        } catch (Exception ignored) {
                            // 闭合失败不影响退出路径
                        }
                    }
                }
            }
        } finally {
            if (log != null) {
                try {
                    log.turnEnded(turn, exit.reason, modelId, mode, exit.error);
                } catch (Exception ignored) {
                    // turn 闭合事件是冷恢复契约的关键，但绝不允许在这里再抛
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 事件流辅助
    // ---------------------------------------------------------------------------

    /** turn 退出状态（finally 闭合 turn/end 用）。默认 error，正常退出点显式置 completed。 */
    private static final class TurnExit {
        String reason = SessionEventType.REASON_ERROR;
        String error = null;
    }

    /**
     * 包装服务层注入的 confirm / ask 回调：在其阻塞等待前后写入 interaction 事件，
     * payload 自足（confirm 带 toolName/message/tags/input，ask 带 question/hint/options），
     * 保证后端重启冷恢复后仍能完整渲染决议卡片。原始回调为 null 时（脱离 Server 跑核心）
     * 不安装，保持工具层原有的「保守拒绝 / 错误提示」行为。
     */
    private static void installInteractionWrappers(ToolContext ctx, SessionEventLog log,
                                                   RunCoordinates coords, int turn) {
        ToolContext.UserConfirmCallback originalConfirm = ctx.waitForUserConfirm();
        if (originalConfirm != null) {
            ctx.waitForUserConfirm(new ToolContext.UserConfirmCallback() {
                @Override
                public boolean awaitConfirm(String callId, String confirmId, String toolName,
                                            String message, List<DestructiveTag> tags, JsonNode input)
                        throws Exception {
                    int t = coords.turnNo();
                    int s = coords.stepNo();
                    ObjectNode payload = Json.object();
                    payload.put("turn", t).put("step", s)
                            .put("interactionId", confirmId)
                            .put("kind", SessionEventType.INTERACTION_CONFIRM)
                            .put("callId", callId)
                            .put("toolName", toolName == null ? "" : toolName)
                            .put("message", message == null ? "" : message);
                    ArrayNode tagArr = Json.array();
                    if (tags != null) {
                        for (DestructiveTag tag : tags) tagArr.add(tag.name());
                    }
                    payload.set("tags", tagArr);
                    payload.set("input", input == null ? NullNode.getInstance() : input);
                    log.interactionRequested(payload);
                    ToolContext.UserConfirmResult result;
                    try {
                        // 富回调（ChatService）可表达「等待超时中断」；普通回调走旧 boolean 桥接。
                        if (originalConfirm instanceof ToolContext.UserConfirmCallbackEx ex) {
                            result = ex.awaitConfirmEx(callId, confirmId, toolName, message, tags, input);
                        } else {
                            result = ToolContext.UserConfirmResult.decided(
                                    originalConfirm.awaitConfirm(callId, confirmId, toolName, message, tags, input),
                                    false);
                        }
                    } catch (Exception e) {
                        log.interactionResolved(t, s, confirmId, SessionEventType.INTERACTION_CONFIRM,
                                false, null, true, true);
                        throw e;
                    }
                    if (result.interrupted()) {
                        // 等待超时等非用户闭合：按拒绝放行给工具层，但决议事件标记 interrupted。
                        log.toolDecided(t, s, callId, "deny", "user",
                                ctx.executionMode(), "interaction-interrupted");
                        log.interactionResolved(t, s, confirmId, SessionEventType.INTERACTION_CONFIRM,
                                false, null, true, true);
                        return false;
                    }
                    boolean approved = result.approved();
                    log.toolDecided(t, s, callId, approved ? "allow" : "deny", "user",
                            ctx.executionMode(), approved ? null : "user-denied");
                    log.interactionResolved(t, s, confirmId, SessionEventType.INTERACTION_CONFIRM,
                            approved, null, false, false);
                    return approved;
                }
            });
        }

        ToolContext.AskUserCallback originalAsk = ctx.askUser();
        if (originalAsk != null) {
            ctx.askUser((askId, question, hint, options) -> {
                int t = coords.turnNo();
                int s = coords.stepNo();
                ObjectNode payload = Json.object();
                payload.put("turn", t).put("step", s)
                        .put("interactionId", askId)
                        .put("kind", SessionEventType.INTERACTION_ASK)
                        .put("question", question == null ? "" : question);
                if (hint != null) payload.put("hint", hint);
                ArrayNode optArr = Json.array();
                if (options != null) options.forEach(optArr::add);
                payload.set("options", optArr);
                log.interactionRequested(payload);
                ToolContext.AskUserResult askResult;
                try {
                    if (originalAsk instanceof ToolContext.AskUserCallbackEx ex) {
                        askResult = ex.askUserEx(askId, question, hint, options);
                    } else {
                        askResult = ToolContext.AskUserResult.answered(
                                originalAsk.askUser(askId, question, hint, options));
                    }
                } catch (Exception e) {
                    log.interactionResolved(t, s, askId, SessionEventType.INTERACTION_ASK,
                            null, null, true, true);
                    throw e;
                }
                if (askResult.interrupted()) {
                    log.interactionResolved(t, s, askId, SessionEventType.INTERACTION_ASK,
                            null, null, true, true);
                    return null;
                }
                String answer = askResult.answer();
                boolean skipped = answer == null || answer.isBlank();
                log.interactionResolved(t, s, askId, SessionEventType.INTERACTION_ASK,
                        null, skipped ? null : answer, skipped, false);
                return answer;
            });
        }
    }

    /**
     * 中止 / 异常退出兜底：assistant/message（含 tool-call blocks）已落盘，但工具结果
     * 来不及产生时，为每个调用补一条 interrupted tool/result 事件（事件日志 + 实时 sink
     * 双路），保证下轮 {@code deriveUIMessages()} 折叠出的历史严格满足
     * 「每个 tool_call 必有对应 tool 消息」的 LLM 协议（否则 DeepSeek 返回 400）。
     * 调用方须保证这批调用尚未发出过任何 tool/result（结果统一在执行返回后的循环里发）。
     */
    private static void emitInterruptedToolResults(SessionEventLog log, AgentEventSink sink,
                                                    int turn, int step,
                                                    List<LlmToolCall> toolCalls) {
        if (log == null || toolCalls == null || toolCalls.isEmpty()) return;
        for (LlmToolCall call : toolCalls) {
            try {
                JsonNode argsNode = parseOrText(call.arguments());
                ObjectNode interruptedOut = Json.object()
                        .put("error", "会话中断，该工具调用未完成（interrupted）；请重新确认后再执行")
                        .put("interrupted", true);
                log.toolResult(turn, step, call.id(), call.name(), interruptedOut, true,
                        null, null, hostKeyOf(argsNode), true);
                // sink → recorder（旧 messages 表投影）；v1 连接已断时推送失败无害
                sink.accept(new ToolResultEvent(call.id(), call.name(), interruptedOut, true));
            } catch (Exception ignored) {
                // 兜底路径自身不得再抛（外层正在退出）
            }
        }
    }

    /** 一条 assistant 消息组装完成（权威 surface）：reasoning/text/tool-call blocks + usage。 */
    private static void emitAssistantMessage(SessionEventLog log, int turn, int step, String modelId,
                                             StringBuilder reasoningBuf, String text,
                                             List<LlmToolCall> toolCalls, UsageStats usage,
                                             List<Integer> chunkSeqs) {
        ArrayNode blocks = Json.array();
        if (reasoningBuf.length() > 0) {
            blocks.addObject().put("kind", "reasoning").put("text", reasoningBuf.toString());
        }
        if (text != null && !text.isEmpty()) {
            blocks.addObject().put("kind", "text").put("text", text);
        }
        if (toolCalls != null) {
            for (LlmToolCall tc : toolCalls) {
                JsonNode inputNode = parseOrText(tc.arguments());
                ObjectNode b = blocks.addObject();
                b.put("kind", "tool-call").put("callId", tc.id()).put("name", tc.name());
                b.set("input", inputNode == null ? NullNode.getInstance() : inputNode);
            }
        }
        ObjectNode usageNode = null;
        if (usage != null) {
            usageNode = Json.object();
            if (usage.promptTokens() != null) usageNode.put("input", usage.promptTokens());
            if (usage.completionTokens() != null) usageNode.put("output", usage.completionTokens());
            if (usage.totalTokens() != null) usageNode.put("total", usage.totalTokens());
        }
        int[] sources = chunkSeqs.isEmpty() ? null
                : chunkSeqs.stream().mapToInt(Integer::intValue).toArray();
        log.assistantMessage(turn, step, blocks, null, modelId, usageNode, sources);
    }

    private static String categoryOf(ToolRegistry registry, String toolName) {
        try {
            var def = registry.find(toolName);
            return def == null || def.category() == null ? null : def.category().name();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从工具参数里取主机标识（run_ssh/sftp_* 的 host_key 字段；兼容 hostKey 写法）。 */
    private static String hostKeyOf(JsonNode args) {
        if (args == null || !args.isObject()) return null;
        JsonNode v = args.get("host_key");
        if (v == null || v.isNull()) v = args.get("hostKey");
        return v == null || v.isNull() ? null : v.asText(null);
    }

    // ---------------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------------
    private static boolean isAborted(BooleanSupplier aborted) {
        return aborted != null && aborted.getAsBoolean();
    }

    /**
     * 步边界 drain 中途插话：供应器一次性取走缓冲的全部用户指示（多条由服务层合并），
     * 作为 user 消息注入。供应器异常/空文本均静默忽略，不反噬主循环。
     *
     * @param injectSink 事件日志 user/message(inject) 落点；null 表示未接事件流
     */
    private static void drainInterjections(AgentLoopOptions options, List<LlmMessage> messages,
                                           Consumer<String> injectSink) {
        if (options == null || options.interjections() == null) return;
        String text;
        try {
            text = options.interjections().get();
        } catch (Exception e) {
            return;
        }
        if (text == null || text.isBlank()) return;
        String injected = "[用户中途指示] " + text.strip()
                + "\n（请在不丢失当前任务主线的前提下采纳；若与原任务冲突，以最新指示为准）";
        messages.add(LlmMessage.user(injected));
        if (injectSink != null) injectSink.accept(injected);
    }

    private static JsonNode parseOrText(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) return null;
        try {
            return Json.parse(arguments);
        } catch (Exception e) {
            return Json.MAPPER.getNodeFactory().textNode(arguments);
        }
    }

    private static String formatError(Throwable err) {
        if (err == null) return "未知错误";
        String msg = err.getMessage();
        if (msg != null && !msg.isEmpty()) return msg;
        return err.getClass().getSimpleName();
    }

    /**
     * 生成「工具名称 + 规范化参数」指纹，用于死循环检测。
     * 参数做递归 key 排序，避免 {@code {b:2,a:1}} 与 {@code {a:1,b:2}} 被误判为不同调用；
     * 参数非 JSON（或解析失败）时回退为原始字符串比对。
     */
    private static String fingerprint(LlmToolCall tc) {
        if (tc == null) return "";
        String base = tc.name() == null ? "" : tc.name();
        try {
            JsonNode n = Json.parse(tc.arguments());
            return base + ":" + canonical(n);
        } catch (Exception e) {
            String args = tc.arguments() == null ? "" : tc.arguments().trim();
            return base + ":" + args;
        }
    }

    /** 递归生成顺序无关的规范化 JSON 文本。 */
    private static String canonical(JsonNode node) {
        if (node == null) return "";
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(',');
                String k = names.get(i);
                sb.append('"').append(k).append("\":").append(canonical(obj.get(k)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        return node.toString();
    }
}
