package com.aye.ai.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 事件溯源会话日志（dsh {@code Session} 的 Java 对应物）。
 *
 * <p>一个会话一个实例：append-only 的 {@link SessionEvent} 序列是唯一事实源，
 * LLM 消息历史由 {@link #deriveUIMessages()} 从 surface 事件折叠得到，从不另存。
 *
 * <p>提交协议（对齐 dsh）：校验 payload → 分配 seq/time → 入日志（提交点）→
 * 同步通知订阅者（持久化批量缓冲 / SSE 推送都是订阅者，且必须非阻塞）。
 * 订阅者抛错只被记录、不能让已提交的 append 失败。
 *
 * <p>崩溃恢复：{@link #balanceInterrupted()} 在冷加载后调用，发现有 turn/start
 * 无 turn/end 时不截断（已落盘事件全部保留），而是补合成闭合事件（未决交互置为
 * interrupted 跳过、未完成工具配错误结果、闭合 step/turn），reason 为
 * {@link SessionEventType#REASON_INTERRUPTED}——循环永不主动发射该值。
 */
public final class SessionEventLog {

    /** 磁盘格式版本：加载时版本不符直接拒绝，不做静默迁移。 */
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> KNOWN_TYPES = Set.of(
            SessionEventType.TURN_START, SessionEventType.TURN_END,
            SessionEventType.STEP_START, SessionEventType.STEP_END,
            SessionEventType.USER_MESSAGE, SessionEventType.ASSISTANT_CHUNK,
            SessionEventType.ASSISTANT_MESSAGE, SessionEventType.TOOL_CALL,
            SessionEventType.TOOL_DECIDED, SessionEventType.TOOL_RESULT,
            SessionEventType.INTERACTION_REQUESTED, SessionEventType.INTERACTION_RESOLVED,
            SessionEventType.REQUEST_HEADER, SessionEventType.COMPACTION_SUMMARY,
            SessionEventType.TERMINAL_MIRROR, SessionEventType.CONTEXT_INJECTED,
            SessionEventType.ERROR,
            SessionEventType.SESSION_END_SEED);

    private final String sessionId;
    private final List<SessionEvent> log = new ArrayList<>();
    private final List<Consumer<SessionEvent>> subscribers = new ArrayList<>();

    public SessionEventLog(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public static boolean isKnownType(String type) {
        return KNOWN_TYPES.contains(type);
    }

    // ------------------------------------------------------------------
    // 订阅（热路径非阻塞契约：订阅者只允许入缓冲，不得做阻塞 IO）
    // ------------------------------------------------------------------
    public void subscribe(Consumer<SessionEvent> subscriber) {
        synchronized (subscribers) {
            subscribers.add(subscriber);
        }
    }

    // ------------------------------------------------------------------
    // 基础 append
    // ------------------------------------------------------------------

    /**
     * 提交一个事件。surface 事件（user/message、assistant/message、tool/result）
     * 自动带 {@code surfaceOp=append}。
     */
    public SessionEvent append(String type, int turnNo, int stepNo, ObjectNode data) {
        return append(type, turnNo, stepNo, data, false, defaultSurface(type), null);
    }

    /** 提交 ignorable（纯展示、丢失不影响重建）事件，如终端镜像。 */
    public SessionEvent appendIgnorable(String type, int turnNo, int stepNo, ObjectNode data) {
        return append(type, turnNo, stepNo, data, true, null, null);
    }

    public SessionEvent append(String type, int turnNo, int stepNo, ObjectNode data,
                               boolean ignorable, String surfaceOp, int[] sourceSeqs) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("session event type must not be blank");
        }
        if (data == null) {
            throw new IllegalArgumentException("session event '" + type + "' data must not be null");
        }
        final SessionEvent event;
        final List<Consumer<SessionEvent>> snapshot;
        synchronized (this) {
            event = new SessionEvent(type, log.size(), System.currentTimeMillis(),
                    turnNo, stepNo, ignorable, surfaceOp, sourceSeqs, data);
            log.add(event);
            synchronized (subscribers) {
                snapshot = new ArrayList<>(subscribers);
            }
            // 提交后通知：严格按提交顺序；订阅者失败被 containment，不影响事实。
            for (Consumer<SessionEvent> s : snapshot) {
                try {
                    s.accept(event);
                } catch (Exception e) {
                    System.getLogger(SessionEventLog.class.getName())
                            .log(System.Logger.Level.WARNING, "session event subscriber failed", e);
                }
            }
            return event;
        }
    }

    /**
     * 接收一个持久化重建事件（restore 路径）：seq 必须严格等于当前日志长度，
     * 保证连续性契约。
     */
    public synchronized void adopt(SessionEvent event) {
        if (event == null) throw new IllegalArgumentException("restored event is null");
        if (event.seq() != log.size()) {
            throw new IllegalStateException("non-contiguous restored event seq "
                    + event.seq() + " expected " + log.size());
        }
        log.add(event);
    }

    // ------------------------------------------------------------------
    // 领域发射点（AgentLoop / ChatService 使用；payload 形状即跨栈契约）
    // ------------------------------------------------------------------

    public SessionEvent turnStarted(int turn, String modelId, String mode) {
        ObjectNode d = Json.object().put("turn", turn);
        if (modelId != null) d.put("modelId", modelId);
        if (mode != null) d.put("mode", mode);
        return append(SessionEventType.TURN_START, turn, SessionEvent.NO_COORD, d);
    }

    public SessionEvent turnEnded(int turn, String reason, String modelId, String mode, String errorMessage) {
        ObjectNode d = Json.object().put("turn", turn).put("reason", reason);
        if (modelId != null) d.put("modelId", modelId);
        if (mode != null) d.put("mode", mode);
        if (errorMessage != null) d.put("errorMessage", errorMessage);
        return append(SessionEventType.TURN_END, turn, SessionEvent.NO_COORD, d);
    }

    public SessionEvent stepStarted(int turn, int step) {
        return append(SessionEventType.STEP_START, turn, step,
                Json.object().put("turn", turn).put("step", step));
    }

    public SessionEvent stepEnded(int turn, int step) {
        return append(SessionEventType.STEP_END, turn, step,
                Json.object().put("turn", turn).put("step", step));
    }

    /**
     * @param source {@link SessionEventType#SOURCE_USER} 真人发言；
     *               {@link SessionEventType#SOURCE_INJECT} 死循环警示 / 收尾 / 继续指令等
     */
    public SessionEvent userMessage(int turn, String content, String source) {
        ObjectNode d = Json.object().put("content", content).put("source", source);
        return append(SessionEventType.USER_MESSAGE, turn, SessionEvent.NO_COORD, d);
    }

    /** 流式块：{blockIndex, chunkType, text?, callId?, name?, argumentsDelta?, usage?}。 */
    public SessionEvent chunk(int turn, int step, ObjectNode chunk) {
        return append(SessionEventType.ASSISTANT_CHUNK, turn, step, chunk);
    }

    /**
     * 组装完成的 assistant 消息。
     *
     * @param blocks ArrayNode，元素 {kind:'text'|'reasoning', text} 或
     *               {kind:'tool-call', callId, name, input}
     * @param usage  可为 null；{input, output, reasoning, cacheRead, cacheWrite}
     */
    public SessionEvent assistantMessage(int turn, int step, ArrayNode blocks,
                                         String provider, String model,
                                         ObjectNode usage, int[] sourceChunkSeqs) {
        ObjectNode d = Json.object().set("blocks", blocks);
        if (provider != null) d.put("provider", provider);
        if (model != null) d.put("model", model);
        if (usage != null && usage.size() > 0) d.set("usage", usage);
        return append(SessionEventType.ASSISTANT_MESSAGE, turn, step, d,
                false, SessionEvent.SURFACE_APPEND, sourceChunkSeqs);
    }

    public SessionEvent toolCall(int turn, int step, String callId, String toolName,
                                 JsonNode arguments, String hostKey, String category) {
        ObjectNode d = Json.object()
                .put("callId", callId)
                .put("name", toolName)
                .set("arguments", arguments == null ? NullNode.getInstance() : arguments);
        if (hostKey != null) d.put("hostKey", hostKey);
        if (category != null) d.put("category", category);
        return append(SessionEventType.TOOL_CALL, turn, step, d);
    }

    /**
     * 权限决议留痕。
     * @param decision   allow / deny
     * @param decidedBy  auto（会话授权 / 信任级自动放行）/ user（人工点击）
     */
    public SessionEvent toolDecided(int turn, int step, String callId, String decision,
                                    String decidedBy, String mode, String rule) {
        ObjectNode d = Json.object()
                .put("callId", callId)
                .put("decision", decision)
                .put("decidedBy", decidedBy);
        if (mode != null) d.put("mode", mode);
        if (rule != null) d.put("rule", rule);
        return append(SessionEventType.TOOL_DECIDED, turn, step, d);
    }

    public SessionEvent toolResult(int turn, int step, String callId, String toolName,
                                   JsonNode output, boolean isError, Integer exitCode,
                                   Long durationMs, String hostKey,
                                   boolean interrupted) {
        ObjectNode d = Json.object().put("callId", callId);
        // 注意：Jackson 2.18 的 ObjectNode.set 是泛型 <T extends JsonNode> T set(...)，
        // 链式调用时 T 会被推断成上界 JsonNode 而失去 put，故 set 单独成句。
        d.set("output", output == null ? NullNode.getInstance() : output);
        d.put("isError", isError);
        if (toolName != null) d.put("name", toolName);
        if (exitCode != null) d.put("exitCode", exitCode);
        if (durationMs != null) d.put("durationMs", durationMs);
        if (hostKey != null) d.put("hostKey", hostKey);
        if (interrupted) d.put("interrupted", true);
        return append(SessionEventType.TOOL_RESULT, turn, step, d,
                false, SessionEvent.SURFACE_APPEND, null);
    }

    public SessionEvent interactionRequested(ObjectNode payload) {
        int turn = payload.path("turn").asInt(SessionEvent.NO_COORD);
        int step = payload.path("step").asInt(SessionEvent.NO_COORD);
        return append(SessionEventType.INTERACTION_REQUESTED, turn, step, payload);
    }

    public SessionEvent interactionResolved(int turn, int step, String interactionId, String kind,
                                            Boolean approved, String answer, boolean skipped,
                                            boolean interrupted) {
        ObjectNode d = Json.object().put("interactionId", interactionId).put("kind", kind);
        if (approved != null) d.put("approved", approved);
        if (answer != null) d.put("answer", answer);
        d.put("skipped", skipped);
        if (interrupted) d.put("interrupted", true);
        return append(SessionEventType.INTERACTION_RESOLVED, turn, step, d);
    }

    /** 请求头快照（轨迹 system 行）。tools 为该步生效工具名清单（完整 schema 后续扩展）。 */
    public SessionEvent requestHeader(int turn, int step, String reason, String model, String mode,
                                      String systemPrompt, List<String> toolNames) {
        ObjectNode d = Json.object().put("reason", reason);
        if (model != null) d.put("model", model);
        if (mode != null) d.put("mode", mode);
        if (systemPrompt != null) d.put("systemPrompt", systemPrompt);
        ArrayNode tools = Json.array();
        if (toolNames != null) toolNames.forEach(tools::add);
        d.set("tools", tools);
        return append(SessionEventType.REQUEST_HEADER, turn, step, d);
    }

    public SessionEvent compactionSummary(int turn, int step, String summary, int replacedFromSeq, int replacedToSeq) {
        ObjectNode d = Json.object().put("summary", summary)
                .put("replacedFromSeq", replacedFromSeq).put("replacedToSeq", replacedToSeq);
        return append(SessionEventType.COMPACTION_SUMMARY, turn, step, d);
    }

    public SessionEvent errorEvent(int turn, int step, String phase, String message) {
        ObjectNode d = Json.object().put("message", message == null ? "" : message);
        if (phase != null) d.put("phase", phase);
        return append(SessionEventType.ERROR, turn, step, d);
    }

    public SessionEvent endSeed() {
        return append(SessionEventType.SESSION_END_SEED, SessionEvent.NO_COORD,
                SessionEvent.NO_COORD, Json.object());
    }

    /**
     * 上下文注入清单行（纯展示，ignorable）：每轮开始时告知前端「本轮纳入了什么」，
     * 如内置工具集名单、系统提示。不参与 {@link #deriveUIMessages()} 折叠。
     */
    public SessionEvent contextInjected(int turn, String name, String summary) {
        ObjectNode d = Json.object();
        if (name != null) d.put("name", name);
        if (summary != null) d.put("summary", summary);
        return appendIgnorable(SessionEventType.CONTEXT_INJECTED, turn,
                SessionEvent.NO_COORD, d);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 当前日志长度 = 下一个事件的 seq。 */
    public synchronized int seq() {
        return log.size();
    }

    public synchronized int lastSeq() {
        return log.size() - 1;
    }

    /** 不可变快照（新列表；事件负载按约定 append 后不再被任何生产者修改）。 */
    public synchronized List<SessionEvent> events() {
        return List.copyOf(log);
    }

    /** 最近一次 turn/end 的 reason；无则 null（用于判断当前是否有 open turn）。 */
    public synchronized String lastTurnEndReason() {
        for (int i = log.size() - 1; i >= 0; i--) {
            SessionEvent e = log.get(i);
            if (e.type().equals(SessionEventType.TURN_END)) return e.data().path("reason").asText(null);
            if (e.type().equals(SessionEventType.TURN_START)) return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // surface 折叠：事件日志 → LLM 消息历史（UIMessage parts 协议）
    // ------------------------------------------------------------------

    /**
     * 从 surface 事件折叠出喂给 LLM / 历史重放共用的 UIMessage 序列。
     * 规则与 dsh deriveMessages 对齐：
     * user/message → user 文本消息；assistant/message → assistant blocks；
     * tool/result → 挂到含同 callId 的 tool-call 的最后一条 assistant 消息；
     * interaction/resolved → 交互记录 part（不送 LLM，仅历史展示）；
     * 其余 chunk / 边界 / request/header 等 log-only 事件一律不投影。
     */
    public synchronized List<UIMessage> deriveUIMessages() {
        List<FoldMsg> folded = new ArrayList<>();
        for (SessionEvent e : log) {
            switch (e.type()) {
                case SessionEventType.USER_MESSAGE -> {
                    List<UIPart> parts = new ArrayList<>();
                    parts.add(new UIPart.TextPart(e.data().path("content").asText("")));
                    folded.add(new FoldMsg("user", parts));
                }
                case SessionEventType.ASSISTANT_MESSAGE -> {
                    List<UIPart> parts = blocksToParts(e.data().get("blocks"));
                    // 空内容且无工具调用的 usage-only 消息不进入模型历史（dsh 同规则）
                    if (!parts.isEmpty()) folded.add(new FoldMsg("assistant", parts));
                }
                case SessionEventType.TOOL_RESULT -> attachToolResult(folded, e);
                case SessionEventType.INTERACTION_RESOLVED -> attachInteraction(folded, e);
                default -> { /* log-only，不投影 */ }
            }
        }
        // 协议配对兜底：turn 在 assistant/message（含 tool-call blocks）落盘后、tool/result
        // 落盘前被取消 / 异常退出时，turn/end(aborted|error) 已闭合，冷恢复不会介入，
        // 历史里会留下「有 tool_call 无 tool 结果」的悬空调用（下一轮喂模触发 400）。
        // 折叠时逐消息补齐合成 interrupted 结果，同时让前端历史重放显示「已中断」。
        for (FoldMsg m : folded) {
            if (!"assistant".equals(m.role)) continue;
            balanceToolResults(m.parts);
        }
        List<UIMessage> out = new ArrayList<>(folded.size());
        for (FoldMsg m : folded) out.add(new UIMessage(m.role, List.copyOf(m.parts)));
        return out;
    }

    /**
     * 保证一条 assistant 消息内每个 tool-call 都有同 id 的 tool-result；
     * 缺失的追加合成错误结果（interrupted）。重复结果不处理（转换层按 id 去重）。
     */
    private static void balanceToolResults(List<UIPart> parts) {
        java.util.Set<String> resultIds = new java.util.HashSet<>();
        List<UIPart.ToolCallPart> missing = new ArrayList<>();
        for (UIPart p : parts) {
            if (p instanceof UIPart.ToolResultPart trp) {
                resultIds.add(trp.id());
            } else if (p instanceof UIPart.ToolCallPart tcp) {
                missing.add(tcp);
            }
        }
        // 先收集缺失 id 再追加（遍历时直接 add 会触发 ArrayList fail-fast）
        for (UIPart.ToolCallPart tcp : missing) {
            if (resultIds.add(tcp.id())) {
                parts.add(new UIPart.ToolResultPart(tcp.id(),
                        Json.object()
                                .put("error", "会话中断，该工具调用未完成（interrupted）；请重新确认后再执行")
                                .put("interrupted", true),
                        true));
            }
        }
    }

    private static List<UIPart> blocksToParts(JsonNode blocks) {
        List<UIPart> parts = new ArrayList<>();
        if (blocks == null || !blocks.isArray()) return parts;
        for (JsonNode b : blocks) {
            String kind = b.path("kind").asText("");
            switch (kind) {
                case "text" -> {
                    String text = b.path("text").asText("");
                    if (!text.isEmpty()) parts.add(new UIPart.TextPart(text));
                }
                case "reasoning" -> {
                    String text = b.path("text").asText("");
                    if (!text.isEmpty()) parts.add(new UIPart.ReasoningPart(text));
                }
                case "tool-call" -> parts.add(new UIPart.ToolCallPart(
                        b.path("callId").asText(""),
                        b.path("name").asText(""),
                        b.get("input")));
                default -> { /* 未知 block 忽略（必需 block 类型加载侧另行拒绝） */ }
            }
        }
        return parts;
    }

    private static void attachToolResult(List<FoldMsg> folded, SessionEvent e) {
        String callId = e.data().path("callId").asText("");
        JsonNode output = e.data().get("output");
        boolean isError = e.data().path("isError").asBoolean(false);
        for (int i = folded.size() - 1; i >= 0; i--) {
            FoldMsg m = folded.get(i);
            if (!"assistant".equals(m.role)) continue;
            for (UIPart p : m.parts) {
                if (p instanceof UIPart.ToolCallPart tc && tc.id().equals(callId)) {
                    m.parts.add(new UIPart.ToolResultPart(callId, output, isError));
                    return;
                }
            }
        }
        // 找不到发起调用（崩在组装消息之前的冷恢复场景）：用合成 tool-call 占位，保证协议配对
        List<UIPart> parts = new ArrayList<>();
        parts.add(new UIPart.ToolCallPart(callId, e.data().path("name").asText(""), null));
        parts.add(new UIPart.ToolResultPart(callId, output, isError));
        folded.add(new FoldMsg("assistant", parts));
    }

    private static void attachInteraction(List<FoldMsg> folded, SessionEvent e) {
        String kind = e.data().path("kind").asText("");
        String id = e.data().path("interactionId").asText("");
        UIPart part = SessionEventType.INTERACTION_ASK.equals(kind)
                ? new UIPart.InteractionAskPart(
                        id,
                        e.data().path("question").asText(""),
                        textOrNull(e.data().get("hint")),
                        toStringList(e.data().get("options")),
                        textOrNull(e.data().get("answer")),
                        e.data().path("skipped").asBoolean(false))
                : new UIPart.InteractionConfirmPart(
                        id,
                        e.data().path("toolName").asText(""),
                        e.data().path("message").asText(""),
                        toStringList(e.data().get("tags")),
                        e.data().get("input"),
                        e.data().path("approved").asBoolean(false));
        for (int i = folded.size() - 1; i >= 0; i--) {
            FoldMsg m = folded.get(i);
            if ("assistant".equals(m.role)) {
                m.parts.add(part);
                return;
            }
        }
        // 冷恢复场景：assistant/message 尚未落盘（崩在组装前），交互 part 无消息可挂。
        // 交互 part 仅历史展示、不送 LLM，故合成一条占位 assistant 消息承载，避免决议留痕丢失。
        List<UIPart> parts = new ArrayList<>();
        parts.add(part);
        folded.add(new FoldMsg("assistant", parts));
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText("");
    }

    private static List<String> toStringList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<String> out = new ArrayList<>(n.size());
        n.forEach(x -> out.add(x.asText("")));
        return out;
    }

    private static final class FoldMsg {
        final String role;
        final List<UIPart> parts;
        FoldMsg(String role, List<UIPart> parts) {
            this.role = role;
            this.parts = parts;
        }
    }

    // ------------------------------------------------------------------
    // 冷恢复：闭合孤儿 turn（不截断、不重放）
    // ------------------------------------------------------------------

    /**
     * 若日志末尾存在未闭合 turn，追加合成闭合事件使其重新平衡。
     *
     * @return true 表示执行了修复（调用方应在修复后 await 一次持久化 flush）
     */
    public synchronized boolean balanceInterrupted() {
        Integer openTurn = null;
        Integer openStep = null;
        // 存整个事件而非 payload：工具结果要挂回 call 自身的 step 坐标，
        // tool/call 的 payload 里没有 step 字段（坐标在事件外层）。
        Map<String, SessionEvent> pendingCalls = new LinkedHashMap<>();
        Map<String, ObjectNode> pendingInteractions = new LinkedHashMap<>();
        for (SessionEvent e : log) {
            switch (e.type()) {
                case SessionEventType.TURN_START -> {
                    openTurn = e.data().path("turn").asInt();
                    openStep = null;
                    pendingCalls.clear();
                    pendingInteractions.clear();
                }
                case SessionEventType.TURN_END -> {
                    openTurn = null;
                    openStep = null;
                    pendingCalls.clear();
                    pendingInteractions.clear();
                }
                case SessionEventType.STEP_START -> openStep = e.data().path("step").asInt();
                case SessionEventType.STEP_END -> openStep = null;
                case SessionEventType.TOOL_CALL ->
                        pendingCalls.put(e.data().path("callId").asText(""), e);
                case SessionEventType.TOOL_RESULT -> pendingCalls.remove(e.data().path("callId").asText(""));
                case SessionEventType.INTERACTION_REQUESTED ->
                        pendingInteractions.put(e.data().path("interactionId").asText(""), e.data());
                case SessionEventType.INTERACTION_RESOLVED ->
                        pendingInteractions.remove(e.data().path("interactionId").asText(""));
                default -> { }
            }
        }
        if (openTurn == null) return false;
        final int turn = openTurn;
        final int step = openStep == null ? SessionEvent.NO_COORD : openStep;

        // 顺序约定：先补未完成工具的错误结果（折叠时建立/落到 assistant 消息），
        // 再补交互决议（挂到同一条 assistant），保证冷恢复投影仍是单条助手消息。
        // 未完成工具：配错误结果（前端/折叠据此渲染「已中断」）。
        // 坐标用 call 事件自身的 stepNo；仅当旧数据缺坐标时回退到当前 openStep。
        for (SessionEvent call : pendingCalls.values()) {
            ObjectNode callData = call.data();
            int callStep = call.stepNo() != SessionEvent.NO_COORD ? call.stepNo() : step;
            ObjectNode errOut = Json.object()
                    .put("error", "会话中断，该工具调用未完成（interrupted）；如需继续请重新确认后执行")
                    .put("interrupted", true);
            toolResult(turn, callStep,
                    callData.path("callId").asText(""), callData.path("name").asText(""),
                    errOut, true, null, null,
                    textOrNull(callData.get("hostKey")), true);
        }
        // 未决交互：标记 interrupted + skipped（绝不自动重放副作用）
        for (Map.Entry<String, ObjectNode> p : pendingInteractions.entrySet()) {
            ObjectNode req = p.getValue();
            interactionResolved(turn, req.path("step").asInt(step), p.getKey(),
                    req.path("kind").asText(SessionEventType.INTERACTION_CONFIRM),
                    null, null, true, true);
        }
        if (openStep != null) stepEnded(turn, openStep);
        turnEnded(turn, SessionEventType.REASON_INTERRUPTED, null, null,
                "会话在执行过程中断开，已由冷恢复闭合");
        return true;
    }

    private static String defaultSurface(String type) {
        return switch (type) {
            case SessionEventType.USER_MESSAGE,
                 SessionEventType.ASSISTANT_MESSAGE,
                 SessionEventType.TOOL_RESULT -> SessionEvent.SURFACE_APPEND;
            default -> null;
        };
    }
}
