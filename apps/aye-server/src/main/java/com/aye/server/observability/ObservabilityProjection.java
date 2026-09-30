package com.aye.server.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventType;
import com.aye.ai.util.Json;
import com.aye.server.session.SessionEventStore;
import com.aye.server.storage.Database;
import com.aye.server.storage.HostStore;
import com.aye.server.storage.SessionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 观测中心只读投影：事件日志（{@code session_events}/{@code session_turns}）是唯一事实源，
 * 「操作日志」与「Agent 追踪」都是它的下游视图。
 *
 * <ul>
 *   <li>{@link #activity}：把 tool/call + tool/decided + tool/result 按 callId 聚合成一次工具操作行，
 *       语义对齐旧 audit_logs，但天然带 sessionId/turn/seq，可与对话双向跳转；</li>
 *   <li>{@link #turns}：turn 投影表 + 事件聚合（步数 / 工具数 / 交互数 / 错误数 / token 用量）；</li>
 *   <li>{@link #trajectory}：单会话全量事件 fold 为时间线行（user / request / assistant / tool /
 *       interaction / compaction / error / turn 边界）。</li>
 * </ul>
 *
 * 桌面单机量级，activity 在内存过滤分页（近期 4000 个工具事件窗口），不新增写表。
 */
public final class ObservabilityProjection {

    /** activity 扫描窗口：最近 N 个工具相关事件（含 call/decided/result/turn-end）。 */
    private static final int ACTIVITY_SCAN_CAP = 6000;
    private static final int MAX_OUTPUT = 4000;
    private static final int MAX_TEXT = 600;
    private static final int MAX_REASONING = 400;
    private static final Pattern EXIT_PATTERN = Pattern.compile("\\[exit=(\\d+)\\]");

    private final Database db;
    private final HostStore hostStore;
    private final SessionStore sessionStore;
    private final SessionEventStore eventStore;

    public ObservabilityProjection(Database db, HostStore hostStore,
                                   SessionStore sessionStore, SessionEventStore eventStore) {
        this.db = db;
        this.hostStore = hostStore;
        this.sessionStore = sessionStore;
        this.eventStore = eventStore;
    }

    // ============================================================
    // 操作日志（activity）
    // ============================================================

    public record ActivityResult(List<Map<String, Object>> rows, long total,
                                 Map<String, Object> stats, List<String> tools) {}

    public ActivityResult activity(Map<String, Object> opts) {
        // hosts 全表只扫一遍，同时建名称 / id 两个映射。
        Map<String, String> hostNameByKey = new LinkedHashMap<>();
        Map<String, Long> hostIdByKey = new LinkedHashMap<>();
        for (Map<String, Object> h : hostStore.findAll()) {
            Object key = h.get("hostKey");
            if (key == null) continue;
            Object name = h.get("name");
            if (name != null) hostNameByKey.put(key.toString(), name.toString());
            if (h.get("id") instanceof Number n) hostIdByKey.put(key.toString(), n.longValue());
        }
        Map<String, String> sessionTitleById = sessionTitleById();

        // 近期工具事件（DESC），同时取 turn/end 以判定「整轮冷中断」的在途工具。
        List<Map<String, Object>> raw = db.query("""
                SELECT session_id AS sid, seq, turn_no AS turn, step_no AS step,
                       type, data, ts_ms AS ts
                  FROM session_events
                 WHERE type IN ('tool/call','tool/decided','tool/result','turn/end')
                 ORDER BY ts_ms DESC""" + " LIMIT " + ACTIVITY_SCAN_CAP);

        // 被冷恢复/冷中断闭合的 turn（balanceInterrupted 之后 reason=interrupted）。
        java.util.Set<String> interruptedTurns = new java.util.HashSet<>();
        // (sessionId, callId) -> row：callId 必须带会话维度，序号型 id 网关
        // （call_0/call_1）会跨会话碰撞，裸 callId 会把不同会话的工具串台。
        Map<String, Map<String, Object>> byCall = new LinkedHashMap<>();

        for (Map<String, Object> r : raw) {
            String type = String.valueOf(r.get("type"));
            ObjectNodeView d = new ObjectNodeView(r.get("data"));
            if (SessionEventType.TURN_END.equals(type)
                    && SessionEventType.REASON_INTERRUPTED.equals(d.text("reason", null))) {
                interruptedTurns.add(r.get("sid") + "\u0000" + r.get("turn"));
                continue;
            }
            String callId = d.text("callId", null);
            if (callId == null || callId.isBlank()) continue;
            String callKey = r.get("sid") + "\u0000" + callId;

            Map<String, Object> row = byCall.computeIfAbsent(callKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("callId", callId);
                m.put("sessionId", String.valueOf(r.get("sid")));
                m.put("seq", num(r.get("seq")));
                m.put("turnNo", num(r.get("turn")));
                m.put("stepNo", num(r.get("step")));
                m.put("tsMs", num(r.get("ts")));
                m.put("arguments", null);
                m.put("hostKey", null);
                m.put("decision", null);
                m.put("decidedBy", null);
                m.put("hasResult", false);
                m.put("isError", false);
                m.put("interrupted", false);
                return m;
            });

            switch (type) {
                case SessionEventType.TOOL_CALL -> {
                    row.put("toolName", d.text("name", "tool"));
                    JsonNode args = d.node("arguments");
                    row.put("arguments", args == null || args.isNull() ? null : args);
                    String hk = d.text("hostKey", null);
                    if (hk == null && args != null && args.isObject()) {
                        hk = args.path("host_key").isTextual() ? args.path("host_key").asText() : null;
                    }
                    row.put("hostKey", hk);
                }
                case SessionEventType.TOOL_DECIDED -> {
                    row.put("decision", d.text("decision", null));
                    row.put("decidedBy", d.text("decidedBy", null));
                    if (row.get("mode") == null) row.put("mode", d.text("mode", null));
                }
                case SessionEventType.TOOL_RESULT -> {
                    row.put("hasResult", true);
                    row.put("isError", d.bool("isError", false));
                    row.put("interrupted", d.bool("interrupted", false));
                    Integer exit = d.intVal("exitCode");
                    if (exit == null) exit = exitCodeOf(outputToText(d.node("output")));
                    row.put("exitCode", exit);
                    Long dur = d.longVal("durationMs");
                    if (dur != null) row.put("durationMs", dur);
                    String out = clip(outputToText(d.node("output")));
                    row.put("output", out);
                    row.put("resultSeq", num(r.get("seq")));
                }
                default -> { /* turn/end 已在上方处理 */ }
            }
        }

        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : byCall.values()) {
            String toolName = String.valueOf(row.getOrDefault("toolName", "tool"));
            JsonNode args = (JsonNode) row.get("arguments");
            String hostKey = (String) row.get("hostKey");
            String sessionId = (String) row.get("sessionId");
            long turnNo = ((Number) row.get("turnNo")).longValue();

            row.put("sessionTitle", sessionTitleById.getOrDefault(sessionId, null));
            if (hostKey != null) {
                row.put("hostName", hostNameByKey.get(hostKey));
                row.put("hostId", hostIdByKey.get(hostKey));
            }

            boolean hasResult = Boolean.TRUE.equals(row.get("hasResult"));
            boolean interrupted = Boolean.TRUE.equals(row.get("interrupted"))
                    || (!hasResult && interruptedTurns.contains(sessionId + "\u0000" + turnNo));
            String status;
            if (!hasResult) status = interrupted ? "interrupted" : "running";
            else if (interrupted) status = "interrupted";
            else if (Boolean.TRUE.equals(row.get("isError"))) status = "failed";
            else status = "success";
            row.put("status", status);
            row.put("interrupted", interrupted);

            String decision = (String) row.get("decision");
            String approval;
            if ("deny".equals(decision)) approval = "rejected";
            else if ("allow".equals(decision) && "user".equals(row.get("decidedBy"))) approval = "approved";
            else if (decision != null) approval = "auto";
            else approval = "running".equals(status) ? "pending" : "auto";
            row.put("approval", approval);
            if (!row.containsKey("durationMs") && hasResult) row.put("durationMs", null);

            String command = commandText(toolName, args);
            row.put("command", command);
            if (!row.containsKey("output")) row.put("output", null);
            // running 行给一个实时已耗时，前端直接展示
            if (!hasResult && !interrupted) row.put("elapsedMs", now - ((Number) row.get("tsMs")).longValue());

            rows.add(row);
        }
        // 按调用时间倒序
        rows.sort((a, b) -> Long.compare(((Number) b.get("tsMs")).longValue(),
                ((Number) a.get("tsMs")).longValue()));

        // ---- 内存过滤 ----
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!match(row, opts, hostIdByKey)) continue;
            filtered.add(row);
        }

        Map<String, Object> stats = statsOf(rows);
        List<String> tools = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String t = String.valueOf(row.get("toolName"));
            if (!tools.contains(t)) tools.add(t);
        }
        tools.sort(String::compareTo);

        long limit = opts.get("limit") instanceof Number n ? n.longValue() : 100;
        long offset = opts.get("offset") instanceof Number n ? n.longValue() : 0;
        List<Map<String, Object>> page = new ArrayList<>();
        for (long i = offset; i < Math.min(offset + limit, filtered.size()); i++) {
            page.add(filtered.get((int) i));
        }
        // 清理内部字段
        for (Map<String, Object> row : page) {
            row.remove("hasResult");
            row.remove("decision");
            row.remove("decidedBy");
        }
        return new ActivityResult(page, filtered.size(), stats, tools);
    }

    private boolean match(Map<String, Object> row, Map<String, Object> opts,
                          Map<String, Long> hostIdByKey) {
        String toolName = str(opts.get("toolName"));
        if (toolName != null && !toolName.isBlank() && !toolName.equals(row.get("toolName"))) return false;

        String status = str(opts.get("status"));
        if (status != null && !status.isBlank() && !status.equals(row.get("status"))) return false;

        String approval = str(opts.get("approvalStatus"));
        if (approval != null && !approval.isBlank() && !approval.equals(row.get("approval"))) return false;

        String sessionId = str(opts.get("sessionId"));
        if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(row.get("sessionId"))) return false;

        Object hostId = opts.get("hostId");
        if (hostId instanceof Number n) {
            Object hk = row.get("hostKey");
            Long rowHostId = hk instanceof String key ? hostIdByKey.get(key) : null;
            if (rowHostId == null || rowHostId.longValue() != n.longValue()) return false;
        }
        Object startMs = opts.get("startMs");
        Object endMs = opts.get("endMs");
        long ts = ((Number) row.get("tsMs")).longValue();
        if (startMs instanceof Number s && ts < s.longValue()) return false;
        if (endMs instanceof Number e && ts > e.longValue()) return false;

        String search = str(opts.get("search"));
        if (search != null && !search.isBlank()) {
            String q = search.toLowerCase();
            String hay = String.valueOf(row.getOrDefault("command", "")).toLowerCase()
                    + "\n" + String.valueOf(row.getOrDefault("output", "")).toLowerCase()
                    + "\n" + String.valueOf(row.getOrDefault("toolName", "")).toLowerCase()
                    + "\n" + String.valueOf(row.getOrDefault("sessionTitle", "")).toLowerCase()
                    + "\n" + argsText(row.get("arguments")).toLowerCase();
            if (!hay.contains(q)) return false;
        }
        return true;
    }

    private Map<String, Object> statsOf(List<Map<String, Object>> rows) {
        long success = 0, failed = 0, interrupted = 0, running = 0;
        long today = 0;
        long dayStart = todayStartMs();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String st = String.valueOf(row.get("status"));
            byStatus.merge(st, 1L, Long::sum);
            switch (st) {
                case "success" -> success++;
                case "failed" -> failed++;
                case "interrupted" -> interrupted++;
                case "running" -> running++;
                default -> { }
            }
            if (((Number) row.get("tsMs")).longValue() >= dayStart) today++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", (long) rows.size());
        out.put("success", success);
        out.put("failed", failed);
        out.put("interrupted", interrupted);
        out.put("running", running);
        out.put("today", today);
        out.put("byStatus", byStatus);
        return out;
    }

    // ============================================================
    // turn 列表（Agent 追踪主表）
    // ============================================================

    public record TurnResult(List<Map<String, Object>> turns, long total) {}

    public TurnResult turns(Map<String, Object> opts) {
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        String search = str(opts.get("search"));
        if (search != null && !search.isBlank()) {
            conds.add("(s.title LIKE ? OR t.session_id LIKE ? OR t.model_id LIKE ?)");
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String state = str(opts.get("state"));
        if (state != null && !state.isBlank()) {
            if ("running".equals(state)) {
                conds.add("t.status = 'running'");
            } else {
                conds.add("t.status = 'done' AND t.reason = ?");
                params.add(state);
            }
        }
        String mode = str(opts.get("mode"));
        if (mode != null && !mode.isBlank()) {
            conds.add("t.mode = ?");
            params.add(mode);
        }
        Object hostId = opts.get("hostId");
        if (hostId instanceof Number n) {
            conds.add("s.host_id = ?");
            params.add(n.longValue());
        }
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);

        long total = 0;
        List<Map<String, Object>> cnt = db.query(
                "SELECT COUNT(*) AS c FROM session_turns t "
                        + "LEFT JOIN sessions s ON s.id = t.session_id "
                        + "LEFT JOIN hosts h ON h.id = s.host_id" + where,
                params.toArray());
        if (!cnt.isEmpty()) total = ((Number) cnt.get(0).get("c")).longValue();

        long limit = opts.get("limit") instanceof Number n ? n.longValue() : 100;
        long offset = opts.get("offset") instanceof Number n ? n.longValue() : 0;

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);
        // 计数/用量是 session_turns 上的增量物化列（事件刷盘事务内维护），
        // 不再对 session_events（含逐 token chunk 行）做全表 GROUP BY + json_extract。
        List<Map<String, Object>> raw = db.query("""
                SELECT t.session_id AS sid, s.title AS title, s.host_id AS host_id,
                       h.name AS host_name,
                       t.turn_no AS turn, t.status AS tstatus, t.reason AS reason,
                       t.model_id AS model, t.mode AS mode, t.started_ms AS started, t.ended_ms AS ended,
                       t.steps AS steps, t.tool_calls AS tool_calls, t.interactions AS interactions,
                       t.errors AS errors, t.tokens_in AS tin, t.tokens_out AS tout,
                       t.tokens_total AS ttot
                  FROM session_turns t
                  LEFT JOIN sessions s ON s.id = t.session_id
                  LEFT JOIN hosts h ON h.id = s.host_id
                """ + where + " ORDER BY t.started_ms DESC LIMIT ? OFFSET ?",
                pageParams.toArray());

        List<Map<String, Object>> turns = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", r.get("sid"));
            m.put("sessionTitle", r.get("title"));
            m.put("hostId", r.get("host_id"));
            m.put("hostName", r.get("host_name"));
            m.put("turnNo", num(r.get("turn")));
            String tstatus = String.valueOf(r.get("tstatus"));
            String reason = r.get("reason") == null ? null : String.valueOf(r.get("reason"));
            m.put("state", "running".equals(tstatus) ? "running" : (reason == null ? "completed" : reason));
            m.put("reason", reason);
            m.put("modelId", r.get("model"));
            m.put("mode", r.get("mode"));
            m.put("startedMs", num(r.get("started")));
            m.put("endedMs", r.get("ended"));
            m.put("durationMs", r.get("started") != null && r.get("ended") != null
                    ? num(r.get("ended")) - num(r.get("started")) : null);
            m.put("steps", num(r.get("steps")));
            m.put("toolCalls", num(r.get("tool_calls")));
            m.put("interactions", num(r.get("interactions")));
            m.put("errors", num(r.get("errors")));
            m.put("tokensIn", num(r.get("tin")));
            m.put("tokensOut", num(r.get("tout")));
            m.put("tokensTotal", num(r.get("ttot")));
            turns.add(m);
        }
        return new TurnResult(turns, total);
    }

    // ============================================================
    // 会话列表（Agent 追踪主表：一个会话一行，按主机分组由前端完成）
    // ============================================================

    public record SessionResult(List<Map<String, Object>> sessions, long total) {}

    public SessionResult sessions(Map<String, Object> opts) {
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        String search = str(opts.get("search"));
        if (search != null && !search.isBlank()) {
            conds.add("(s.title LIKE ? OR t.session_id LIKE ? OR t.model_id LIKE ?)");
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String state = str(opts.get("state"));
        if (state != null && !state.isBlank()) {
            if ("running".equals(state)) {
                conds.add("t.status = 'running'");
            } else {
                conds.add("t.status = 'done' AND t.reason = ?");
                params.add(state);
            }
        }
        String mode = str(opts.get("mode"));
        if (mode != null && !mode.isBlank()) {
            conds.add("t.mode = ?");
            params.add(mode);
        }
        Object hostId = opts.get("hostId");
        if (hostId instanceof Number n) {
            conds.add("s.host_id = ?");
            params.add(n.longValue());
        }
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);

        // 桌面单机量级：命中过滤条件的 turn 全量取出（已按开始时间倒序），在内存按会话折叠。
        // 状态过滤命中「含该状态轮次的会话」，例如异常筛选能看到曾经出过问题的完整会话。
        List<Map<String, Object>> raw = db.query("""
                SELECT t.session_id AS sid, s.title AS title, s.host_id AS host_id,
                       h.name AS host_name,
                       t.turn_no AS turn, t.status AS tstatus, t.reason AS reason,
                       t.model_id AS model, t.mode AS mode, t.started_ms AS started, t.ended_ms AS ended,
                       t.steps AS steps, t.tool_calls AS tool_calls, t.interactions AS interactions,
                       t.errors AS errors, t.tokens_in AS tin, t.tokens_out AS tout,
                       t.tokens_total AS ttot
                  FROM session_turns t
                  LEFT JOIN sessions s ON s.id = t.session_id
                  LEFT JOIN hosts h ON h.id = s.host_id
                """ + where + " ORDER BY t.started_ms DESC");

        // sid -> 聚合行；LinkedHashMap + DESC 遍历保证组内/整体都以最近活动优先。
        Map<String, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            String sid = String.valueOf(r.get("sid"));
            Map<String, Object> m = agg.get(sid);
            boolean first = m == null;
            if (first) {
                m = new LinkedHashMap<>();
                m.put("sessionId", sid);
                m.put("sessionTitle", r.get("title"));
                m.put("hostId", r.get("host_id"));
                m.put("hostName", r.get("host_name"));
                m.put("turnCount", 0L);
                m.put("steps", 0L);
                m.put("toolCalls", 0L);
                m.put("interactions", 0L);
                m.put("errors", 0L);
                m.put("tokensIn", 0L);
                m.put("tokensOut", 0L);
                m.put("tokensTotal", 0L);
                m.put("durationMs", 0L);
                m.put("errorTurns", 0L);
                m.put("startedMs", num(r.get("started")));
                m.put("lastMs", num(r.get("started")));
                agg.put(sid, m);
            }
            String tstatus = String.valueOf(r.get("tstatus"));
            String reason = r.get("reason") == null ? null : String.valueOf(r.get("reason"));
            String rowState = "running".equals(tstatus) ? "running"
                    : (reason == null ? "completed" : reason);

            m.put("turnCount", ((Number) m.get("turnCount")).longValue() + 1);
            m.put("steps", ((Number) m.get("steps")).longValue() + num(r.get("steps")));
            m.put("toolCalls", ((Number) m.get("toolCalls")).longValue() + num(r.get("tool_calls")));
            m.put("interactions", ((Number) m.get("interactions")).longValue() + num(r.get("interactions")));
            m.put("errors", ((Number) m.get("errors")).longValue() + num(r.get("errors")));
            m.put("tokensIn", ((Number) m.get("tokensIn")).longValue() + num(r.get("tin")));
            m.put("tokensOut", ((Number) m.get("tokensOut")).longValue() + num(r.get("tout")));
            m.put("tokensTotal", ((Number) m.get("tokensTotal")).longValue() + num(r.get("ttot")));
            if (r.get("started") != null && r.get("ended") != null) {
                m.put("durationMs", ((Number) m.get("durationMs")).longValue()
                        + num(r.get("ended")) - num(r.get("started")));
            }
            if ("error".equals(rowState)) {
                m.put("errorTurns", ((Number) m.get("errorTurns")).longValue() + 1);
            }
            long started = num(r.get("started"));
            if (started < ((Number) m.get("startedMs")).longValue()) m.put("startedMs", started);
            if (started > ((Number) m.get("lastMs")).longValue()) m.put("lastMs", started);

            // 首行（DESC = 最近一轮）决定会话当前状态、模型与模式。
            if (first) {
                m.put("state", rowState);
                m.put("reason", reason);
                m.put("modelId", r.get("model"));
                m.put("mode", r.get("mode"));
            }
        }

        List<Map<String, Object>> all = new ArrayList<>(agg.values());
        long limit = opts.get("limit") instanceof Number n ? n.longValue() : 100;
        long offset = opts.get("offset") instanceof Number n ? n.longValue() : 0;
        List<Map<String, Object>> page = new ArrayList<>();
        for (long i = offset; i < Math.min(offset + limit, all.size()); i++) {
            page.add(all.get((int) i));
        }
        return new SessionResult(page, all.size());
    }

    // ============================================================
    // 单会话 trajectory（时间线）
    // ============================================================

    public Map<String, Object> trajectory(String sessionId) {
        Map<String, String> hostNameByKey = hostNameByKey();
        String title = sessionTitleById().get(sessionId);

        List<SessionEvent> events = eventStore.readAll(sessionId);

        // ---- 第一遍：聚合工具调用 / ask 交互 / 步边界时间 / 首 token 时间 ----
        // key 为 (turnNo, callId)：序号型 callId 跨 turn 会复用，裸 callId 会串台。
        Map<String, Map<String, Object>> toolByCall = new LinkedHashMap<>();
        Map<String, Map<String, Object>> askById = new LinkedHashMap<>();
        Map<Long, Long> stepStartMs = new LinkedHashMap<>();
        Map<Long, Long> firstChunkMs = new LinkedHashMap<>();
        // 命中上下文压缩的步：压缩完成时刻（事件在压缩 LLM 返回后、业务 LLM 请求前发出）。
        // TTFT 必须从此刻起算，否则数秒压缩会被算进首 token 延迟。
        Map<Long, Long> compactDoneMs = new LinkedHashMap<>();
        Map<Integer, long[]> turnTokens = new LinkedHashMap<>(); // turn -> [in,out,total]

        for (SessionEvent e : events) {
            long stepKey = stepKey(e.turnNo(), e.stepNo());
            switch (e.type()) {
                case SessionEventType.STEP_START -> stepStartMs.put(stepKey, e.tsMs());
                case SessionEventType.ASSISTANT_CHUNK -> {
                    if (!firstChunkMs.containsKey(stepKey)) firstChunkMs.put(stepKey, e.tsMs());
                }
                case SessionEventType.COMPACTION_SUMMARY ->
                        compactDoneMs.putIfAbsent(stepKey, e.tsMs());
                case SessionEventType.TOOL_CALL -> {
                    String callId = e.data().path("callId").asText(null);
                    if (callId == null) break;
                    JsonNode args = e.data().get("arguments");
                    String hk = e.data().path("hostKey").asText(null);
                    if (hk == null && args != null && args.isObject()
                            && args.path("host_key").isTextual()) {
                        hk = args.path("host_key").asText();
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("callId", callId);
                    m.put("name", e.data().path("name").asText("tool"));
                    m.put("arguments", args == null || args.isNull() ? null : args);
                    m.put("hostKey", hk);
                    m.put("hostName", hk == null ? null : hostNameByKey.get(hk));
                    m.put("callSeq", e.seq());
                    m.put("turnNo", e.turnNo());
                    m.put("stepNo", e.stepNo());
                    m.put("callTsMs", e.tsMs());
                    m.put("approval", "pending");
                    toolByCall.put(toolKey(e.turnNo(), callId), m);
                }
                case SessionEventType.TOOL_DECIDED -> {
                    Map<String, Object> m = toolByCall.get(toolKey(e.turnNo(),
                            e.data().path("callId").asText("")));
                    if (m == null) break;
                    String decision = e.data().path("decision").asText("");
                    m.put("approval", "deny".equals(decision) ? "rejected"
                            : "allow".equals(decision) && "user".equals(e.data().path("decidedBy").asText(""))
                            ? "approved" : "auto");
                    m.put("decidedBy", e.data().path("decidedBy").asText(null));
                }
                case SessionEventType.TOOL_RESULT -> {
                    Map<String, Object> m = toolByCall.get(toolKey(e.turnNo(),
                            e.data().path("callId").asText("")));
                    if (m == null) break;
                    JsonNode out = e.data().get("output");
                    String text = clip(outputToText(out));
                    boolean isError = e.data().path("isError").asBoolean(false);
                    boolean interrupted = e.data().path("interrupted").asBoolean(false);
                    m.put("isError", isError);
                    m.put("interrupted", interrupted);
                    m.put("status", interrupted ? "interrupted" : isError ? "failed" : "success");
                    m.put("output", text);
                    // exitCode 可空：非 shell 类工具或输出中无 [exit=N] 标记时没有退出码。
                    // 不能写成三元（asInt 是 int，会把 null 的 Integer 拆箱成 NPE）。
                    Integer exitCode = null;
                    if (e.data().has("exitCode") && e.data().get("exitCode").isNumber()) {
                        exitCode = e.data().get("exitCode").asInt();
                    } else {
                        exitCode = exitCodeOf(text);
                    }
                    m.put("exitCode", exitCode);
                    if (e.data().get("durationMs") != null && e.data().get("durationMs").isNumber()) {
                        m.put("durationMs", e.data().get("durationMs").asLong());
                    }
                    m.put("resultTsMs", e.tsMs());
                }
                case SessionEventType.INTERACTION_REQUESTED -> {
                    String kind = e.data().path("kind").asText("");
                    if (SessionEventType.INTERACTION_ASK.equals(kind)) {
                        String iid = e.data().path("interactionId").asText("");
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("interactionId", iid);
                        m.put("question", e.data().path("question").asText(""));
                        m.put("hint", e.data().path("hint").isTextual() ? e.data().path("hint").asText() : null);
                        m.put("options", toStringList(e.data().get("options")));
                        m.put("seq", e.seq());
                        m.put("turnNo", e.turnNo());
                        m.put("stepNo", e.stepNo());
                        m.put("tsMs", e.tsMs());
                        m.put("resolved", false);
                        askById.put(iid, m);
                    } else if (SessionEventType.INTERACTION_CONFIRM.equals(kind)) {
                        Map<String, Object> m = toolByCall.get(toolKey(e.turnNo(),
                                e.data().path("callId").asText("")));
                        if (m != null) {
                            m.put("confirmMessage", e.data().path("message").asText(null));
                            m.put("confirmTags", toStringList(e.data().get("tags")));
                        }
                    }
                }
                case SessionEventType.INTERACTION_RESOLVED -> {
                    String kind = e.data().path("kind").asText("");
                    String iid = e.data().path("interactionId").asText("");
                    if (SessionEventType.INTERACTION_ASK.equals(kind)) {
                        Map<String, Object> m = askById.get(iid);
                        if (m != null) {
                            m.put("resolved", true);
                            m.put("skipped", e.data().path("skipped").asBoolean(false));
                            m.put("interrupted", e.data().path("interrupted").asBoolean(false));
                            m.put("answer", e.data().path("answer").isTextual()
                                    ? e.data().path("answer").asText() : null);
                        }
                    }
                    // confirm 的决议结果已由 tool/decided 体现（allow/deny + decidedBy），无需回填。
                }
                case SessionEventType.ASSISTANT_MESSAGE -> {
                    JsonNode usage = e.data().get("usage");
                    if (usage != null && usage.isObject()) {
                        long[] t = turnTokens.computeIfAbsent(e.turnNo(), k -> new long[3]);
                        t[0] += usage.path("input").asLong(0);
                        t[1] += usage.path("output").asLong(0);
                        t[2] += usage.path("total").asLong(0);
                    }
                }
                default -> { }
            }
        }

        // ---- turn 投影 ----
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Map<String, Object> t : eventStore.listTurns(sessionId)) {
            int turnNo = ((Number) t.get("turn_no")).intValue();
            long[] tk = turnTokens.getOrDefault(turnNo, new long[3]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnNo", turnNo);
            m.put("status", t.get("status"));
            m.put("reason", t.get("reason"));
            m.put("modelId", t.get("model_id"));
            m.put("mode", t.get("mode"));
            m.put("startedMs", t.get("started_ms"));
            m.put("endedMs", t.get("ended_ms"));
            m.put("tokensIn", tk[0]);
            m.put("tokensOut", tk[1]);
            m.put("tokensTotal", tk[2]);
            turns.add(m);
        }

        // ---- 第二遍：按 seq 生成时间线行 ----
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SessionEvent e : events) {
            switch (e.type()) {
                case SessionEventType.TURN_START -> rows.add(row("turn-start", e, m -> {
                    m.put("modelId", e.data().path("modelId").asText(null));
                    m.put("mode", e.data().path("mode").asText(null));
                }));
                case SessionEventType.TURN_END -> rows.add(row("turn-end", e, m -> {
                    m.put("reason", e.data().path("reason").asText("completed"));
                    m.put("errorMessage", e.data().path("errorMessage").asText(null));
                }));
                case SessionEventType.USER_MESSAGE -> rows.add(row("user", e, m -> {
                    m.put("text", clipTo(e.data().path("content").asText(""), MAX_TEXT));
                    m.put("source", e.data().path("source").asText("user"));
                }));
                case SessionEventType.REQUEST_HEADER -> rows.add(row("request", e, m -> {
                    m.put("model", e.data().path("model").asText(null));
                    m.put("mode", e.data().path("mode").asText(null));
                    JsonNode tools = e.data().get("tools");
                    m.put("toolsCount", tools instanceof com.fasterxml.jackson.databind.node.ArrayNode an ? an.size() : 0);
                }));
                case SessionEventType.STEP_START -> rows.add(row("step-start", e, null));
                case SessionEventType.STEP_END -> rows.add(row("step-end", e, m -> {
                    // 步墙钟时长（含压缩/排队），与 assistant 行的 ttftMs（已排除压缩）口径不同。
                    Long s = stepStartMs.get(stepKey(e.turnNo(), e.stepNo()));
                    m.put("durationMs", s == null ? null : e.tsMs() - s);
                }));
                case SessionEventType.ASSISTANT_MESSAGE ->
                        rows.add(buildAssistantRow(e, stepStartMs, firstChunkMs, compactDoneMs));
                case SessionEventType.TOOL_CALL -> {
                    Map<String, Object> t = toolByCall.get(toolKey(e.turnNo(),
                            e.data().path("callId").asText("")));
                    if (t != null) {
                        Map<String, Object> m = new LinkedHashMap<>(t);
                        m.put("kind", "tool");
                        m.put("seq", e.seq());
                        m.put("tsMs", e.tsMs());
                        m.put("command", commandText(String.valueOf(t.get("name")), (JsonNode) t.get("arguments")));
                        if (!m.containsKey("status")) m.put("status", "running");
                        rows.add(m);
                    }
                }
                case SessionEventType.INTERACTION_REQUESTED -> {
                    if (SessionEventType.INTERACTION_ASK.equals(e.data().path("kind").asText(""))) {
                        Map<String, Object> a = askById.get(e.data().path("interactionId").asText(""));
                        if (a != null) {
                            Map<String, Object> m = new LinkedHashMap<>(a);
                            m.put("kind", "interaction");
                            m.put("interactionKind", "ask");
                            rows.add(m);
                        }
                    }
                    // confirm 已聚合进对应 tool 行
                }
                case SessionEventType.COMPACTION_SUMMARY -> rows.add(row("compaction", e, m -> {
                    m.put("summary", clipTo(e.data().path("summary").asText(""), MAX_TEXT));
                    m.put("replacedFromSeq", e.data().path("replacedFromSeq").asInt(-1));
                    m.put("replacedToSeq", e.data().path("replacedToSeq").asInt(-1));
                }));
                case SessionEventType.ERROR -> rows.add(row("error", e, m -> {
                    m.put("message", e.data().path("message").asText(""));
                    m.put("phase", e.data().path("phase").asText(null));
                }));
                default -> { /* chunk / decided / result / resolved 已聚合 */ }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", sessionId);
        session.put("title", title);
        out.put("session", session);
        out.put("turns", turns);
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> buildAssistantRow(SessionEvent e,
                                                  Map<Long, Long> stepStartMs,
                                                  Map<Long, Long> firstChunkMs,
                                                  Map<Long, Long> compactDoneMs) {
        Map<String, Object> m = baseRow("assistant", e);
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        JsonNode blocks = e.data().get("blocks");
        if (blocks != null && blocks.isArray()) {
            for (JsonNode b : blocks) {
                String kind = b.path("kind").asText("");
                if ("text".equals(kind)) text.append(b.path("text").asText(""));
                else if ("reasoning".equals(kind)) reasoning.append(b.path("text").asText(""));
                else if ("tool-call".equals(kind)) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("callId", b.path("callId").asText(null));
                    tc.put("name", b.path("name").asText("tool"));
                    toolCalls.add(tc);
                }
            }
        }
        m.put("text", clipTo(text.toString(), MAX_TEXT));
        m.put("reasoning", clipTo(reasoning.toString(), MAX_REASONING));
        m.put("toolCalls", toolCalls);
        m.put("model", e.data().path("model").asText(null));
        JsonNode usage = e.data().get("usage");
        if (usage != null && usage.isObject()) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("input", usage.path("input").asLong(0));
            u.put("output", usage.path("output").asLong(0));
            u.put("total", usage.path("total").asLong(0));
            m.put("usage", u);
        }
        long sk = stepKey(e.turnNo(), e.stepNo());
        Long stepStart = stepStartMs.get(sk);
        Long firstChunk = firstChunkMs.get(sk);
        Long compactDone = compactDoneMs.get(sk);
        m.put("stepStartMs", stepStart);
        m.put("firstChunkMs", firstChunk);
        // TTFT 基點：命中压缩的步从压缩完成起算（压缩本身是独立 LLM 往返，不属于本次首 token 延迟），
        // 未命中压缩仍从 step/start 起算。
        Long ttftBase = compactDone != null ? compactDone : stepStart;
        m.put("ttftMs", ttftBase != null && firstChunk != null ? firstChunk - ttftBase : null);
        m.put("messageTsMs", e.tsMs());
        return m;
    }

    private interface RowFiller {
        void fill(Map<String, Object> m);
    }

    private Map<String, Object> row(String kind, SessionEvent e, RowFiller filler) {
        Map<String, Object> m = baseRow(kind, e);
        if (filler != null) filler.fill(m);
        return m;
    }

    private Map<String, Object> baseRow(String kind, SessionEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("seq", e.seq());
        m.put("turnNo", e.turnNo());
        m.put("stepNo", e.stepNo());
        m.put("tsMs", e.tsMs());
        return m;
    }

    private static long stepKey(int turn, int step) {
        return ((long) turn << 20) | (step & 0xfffffL);
    }

    /** 单会话轨迹内工具聚合键：turn 维度 + callId，防序号型 callId 跨轮复用串台。 */
    private static String toolKey(int turn, String callId) {
        return turn + "\u0000" + (callId == null ? "" : callId);
    }

    // ============================================================
    // 辅助
    // ============================================================

    private Map<String, String> hostNameByKey() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> h : hostStore.findAll()) {
            Object key = h.get("hostKey");
            Object name = h.get("name");
            if (key != null && name != null) out.put(key.toString(), name.toString());
        }
        return out;
    }

    private Map<String, String> sessionTitleById() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> s : sessionStore.listSessions()) {
            Object id = s.get("id");
            Object title = s.get("title");
            if (id != null) out.put(id.toString(), title == null ? null : title.toString());
        }
        return out;
    }

    private static String commandText(String toolName, JsonNode args) {
        if (args == null || !args.isObject()) return toolName;
        String oneOf = firstText(args, "command", "cmd", "query", "url", "pattern", "name", "question");
        if (oneOf != null && !oneOf.isBlank()) return toolName + " " + clipTo(oneOf, 200);
        String path = firstText(args, "path", "filePath", "remotePath");
        if (path != null && !path.isBlank()) return toolName + " " + path;
        return toolName;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String outputToText(JsonNode out) {
        if (out == null || out.isNull()) return "";
        if (out.isTextual()) return out.asText();
        if (out.isObject()) {
            for (String key : new String[]{"output", "content", "text", "result", "stdout", "message"}) {
                JsonNode v = out.get(key);
                if (v != null && !v.isNull()) {
                    String s = outputToText(v);
                    if (!s.isBlank()) return s;
                }
            }
            return out.toString();
        }
        if (out.isArray()) return out.toString();
        return out.asText();
    }

    private static String argsText(Object args) {
        if (args instanceof JsonNode n) return n.toString();
        return "";
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            for (JsonNode n : arr) out.add(n.asText());
        }
        return out;
    }

    private static Integer exitCodeOf(String text) {
        if (text == null) return null;
        Matcher m = EXIT_PATTERN.matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String clip(String s) {
        return clipTo(s, MAX_OUTPUT);
    }

    private static String clipTo(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return (long) Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long todayStartMs() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** data 列读取小工具（db.query 返回的 data 是 TEXT，需要懒解析 JSON）。 */
    private static final class ObjectNodeView {
        private JsonNode node;
        private final Object raw;

        ObjectNodeView(Object rawData) {
            this.raw = rawData;
        }

        private JsonNode parsed() {
            if (node == null) {
                try {
                    node = Json.parse(String.valueOf(raw));
                } catch (Exception e) {
                    node = Json.object();
                }
            }
            return node;
        }

        String text(String key, String def) {
            JsonNode n = parsed().get(key);
            return n != null && n.isTextual() ? n.asText() : def;
        }

        boolean bool(String key, boolean def) {
            JsonNode n = parsed().get(key);
            return n != null && n.isBoolean() ? n.asBoolean() : def;
        }

        Integer intVal(String key) {
            JsonNode n = parsed().get(key);
            return n != null && n.isNumber() ? n.asInt() : null;
        }

        Long longVal(String key) {
            JsonNode n = parsed().get(key);
            return n != null && n.isNumber() ? n.asLong() : null;
        }

        JsonNode node(String key) {
            return parsed().get(key);
        }
    }
}
