package com.aye.server.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventType;
import com.aye.ai.util.Json;
import com.aye.server.storage.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话事件日志的 SQLite 持久化（{@code session_events} / {@code session_turns}）。
 *
 * <p>行与 {@link SessionEvent} 1:1 映射，没有并行 schema：data 列存 payload JSON，
 * source_seqs 列存 int 数组 JSON（可空）。同一批事件在单事务内顺序 INSERT，
 * 顺带维护 session_turns 投影（turn/start → running，turn/end → done/reason）。
 *
 * <p>所有读写经 {@link Database} 的单连接串行化，无需额外锁。
 */
public final class SessionEventStore {

    private final Database db;

    public SessionEventStore(Database db) {
        this.db = db;
    }

    /**
     * 单事务批量持久化一批事件（按入参顺序，必须 seq 连续递增）。
     * 同时维护 turn 投影表。批内任一失败整体回滚。
     */
    public void persistBatch(String sessionId, List<SessionEvent> batch) {
        if (batch == null || batch.isEmpty()) return;
        db.transaction(() -> {
            for (SessionEvent e : batch) {
                db.update("""
                        INSERT INTO session_events
                          (session_id, seq, type, ignorable, surface_op, source_seqs,
                           turn_no, step_no, data, ts_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        sessionId, e.seq(), e.type(), e.ignorable() ? 1 : 0,
                        e.surfaceOp(), encodeSeqs(e.sourceSeqs()),
                        e.turnNo(), e.stepNo(), Json.stringify(e.data()), e.tsMs());
                maintainTurnProjection(sessionId, e);
            }
        });
    }

    private void maintainTurnProjection(String sessionId, SessionEvent e) {
        switch (e.type()) {
            case SessionEventType.TURN_START -> {
                // 正常路径不会冲突；INSERT OR IGNORE 兼容冷恢复重复打开。
                db.update("""
                        INSERT OR IGNORE INTO session_turns
                          (session_id, turn_no, status, model_id, mode, reason, started_ms, ended_ms)
                        VALUES (?, ?, 'running', ?, ?, NULL, ?, NULL)""",
                        sessionId, e.turnNo(),
                        textOrNull(e.data().get("modelId")),
                        textOrNull(e.data().get("mode")),
                        e.tsMs());
            }
            case SessionEventType.TURN_END -> {
                int n = db.update("""
                        UPDATE session_turns
                           SET status = 'done', reason = ?, model_id = COALESCE(?, model_id),
                               mode = COALESCE(?, mode), ended_ms = ?
                         WHERE session_id = ? AND turn_no = ?""",
                        e.data().path("reason").asText("completed"),
                        textOrNull(e.data().get("modelId")),
                        textOrNull(e.data().get("mode")),
                        e.tsMs(), sessionId, e.turnNo());
                if (n == 0) {
                    // 边缘情况：只有 end 没有 start（旧库/手工数据），补一行。
                    db.update("""
                            INSERT INTO session_turns
                              (session_id, turn_no, status, model_id, mode, reason, started_ms, ended_ms)
                            VALUES (?, ?, 'done', ?, ?, ?, ?, ?)""",
                            sessionId, e.turnNo(),
                            textOrNull(e.data().get("modelId")),
                            textOrNull(e.data().get("mode")),
                            e.data().path("reason").asText("completed"),
                            e.tsMs(), e.tsMs());
                }
            }
            // ---- 增量物化观测计数（turn 行已在同事务更早位置由 turn/start 插入）----
            // 口径与原 turns() 派生表一致：计数字段按事件类型 +1，token 按 assistant/message 的 usage 累加。
            case SessionEventType.STEP_START -> bumpTurnCounter(sessionId, e, "steps");
            case SessionEventType.TOOL_CALL -> bumpTurnCounter(sessionId, e, "tool_calls");
            case SessionEventType.INTERACTION_REQUESTED ->
                    bumpTurnCounter(sessionId, e, "interactions");
            case SessionEventType.ERROR -> bumpTurnCounter(sessionId, e, "errors");
            case SessionEventType.ASSISTANT_MESSAGE -> {
                JsonNode usage = e.data().get("usage");
                if (usage != null && usage.isObject()
                        && (usage.path("input").asLong(0) != 0
                        || usage.path("output").asLong(0) != 0
                        || usage.path("total").asLong(0) != 0)) {
                    db.update("""
                            UPDATE session_turns
                               SET tokens_in = tokens_in + ?,
                                   tokens_out = tokens_out + ?,
                                   tokens_total = tokens_total + ?
                             WHERE session_id = ? AND turn_no = ?""",
                            usage.path("input").asLong(0),
                            usage.path("output").asLong(0),
                            usage.path("total").asLong(0),
                            sessionId, e.turnNo());
                }
            }
            default -> { /* 其余事件不影响 turn 投影 */ }
        }
    }

    /**
     * turn 计数器 +1。column 仅来自本类 switch 内的固定白名单，非外部输入，可安全拼接。
     * turn 行不存在时（turnNo 为 NO_COORD 的会话级事件，或冷恢复合成事件先于投影行）
     * UPDATE 影响 0 行，直接忽略：这些事件本就不属于任何 turn。
     */
    private void bumpTurnCounter(String sessionId, SessionEvent e, String column) {
        db.update("UPDATE session_turns SET " + column + " = " + column
                        + " + 1 WHERE session_id = ? AND turn_no = ?",
                sessionId, e.turnNo());
    }

    /** 读取会话全部事件（按 seq 升序）。 */
    public List<SessionEvent> readAll(String sessionId) {
        return queryRows("""
                SELECT seq, type, ignorable, surface_op, source_seqs, turn_no, step_no, data, ts_ms
                  FROM session_events WHERE session_id = ? ORDER BY seq ASC""", sessionId);
    }

    /**
     * 尾页：seq &gt; afterSeq 的事件升序（afterSeq 为负表示从头）。
     * limit 为 null/&le;0 表示不限。
     */
    public List<SessionEvent> readTail(String sessionId, int afterSeq, Integer limit) {
        String sql = """
                SELECT seq, type, ignorable, surface_op, source_seqs, turn_no, step_no, data, ts_ms
                  FROM session_events WHERE session_id = ? AND seq > ? ORDER BY seq ASC"""
                + (limit != null && limit > 0 ? " LIMIT " + limit : "");
        return queryRows(sql, sessionId, afterSeq);
    }

    /**
     * 头向分页：seq &lt; beforeSeq 的最近 limit 条（返回时已翻回升序），
     * 供前端「向上加载历史」拿连续区间，不允许窗口空洞。
     */
    public List<SessionEvent> readBefore(String sessionId, int beforeSeq, int limit) {
        int lim = limit <= 0 ? 100 : limit;
        // LIMIT 动态整数，拼普通字符串；文本块结束符单独成行，避免结尾空格被当成
        // incidental whitespace 清掉（曾经踩过 LIMIT3 的坑）。
        String sql = """
                SELECT seq, type, ignorable, surface_op, source_seqs, turn_no, step_no, data, ts_ms
                  FROM session_events WHERE session_id = ? AND seq < ?
                  ORDER BY seq DESC"""
                + " LIMIT " + lim;
        List<SessionEvent> rows = queryRows(sql, sessionId, beforeSeq);
        Collections.reverse(rows);
        return rows;
    }

    /** 已持久化的最大 seq；无事件返回 -1。 */
    public int maxSeq(String sessionId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT MAX(seq) AS m FROM session_events WHERE session_id = ?", sessionId);
        if (rows.isEmpty()) return -1;
        Object v = rows.get(0).get("m");
        return v == null ? -1 : ((Number) v).intValue();
    }

    public long count(String sessionId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT COUNT(*) AS c FROM session_events WHERE session_id = ?", sessionId);
        Object v = rows.isEmpty() ? null : rows.get(0).get("c");
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** turn 投影（升序）；看板状态投影使用。 */
    public List<Map<String, Object>> listTurns(String sessionId) {
        return db.query("""
                SELECT turn_no, status, model_id, mode, reason, started_ms, ended_ms
                  FROM session_turns WHERE session_id = ? ORDER BY turn_no ASC""", sessionId);
    }

    /**
     * 批量查询各会话当前 running 的最大 turn_no（单条聚合 SQL，避免列表 N+1）。
     * 无运行 turn 的会话不出现在返回 map 中；冷恢复闭合后投影已置 done，重启不会误报。
     */
    public Map<String, Integer> runningTurns(java.util.Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", sessionIds.stream().map(s -> "?").toList());
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> rows = db.query(
                "SELECT session_id, MAX(turn_no) AS turn_no FROM session_turns"
                        + " WHERE status = 'running' AND session_id IN (" + placeholders
                        + ") GROUP BY session_id",
                sessionIds.toArray());
        for (Map<String, Object> row : rows) {
            Object v = row.get("turn_no");
            if (v != null) {
                out.put(String.valueOf(row.get("session_id")), ((Number) v).intValue());
            }
        }
        return out;
    }

    /** 删除会话的全部事件与 turn 投影（会话删除时级联）。 */
    public void deleteSession(String sessionId) {
        db.update("DELETE FROM session_events WHERE session_id = ?", sessionId);
        db.update("DELETE FROM session_turns WHERE session_id = ?", sessionId);
    }

    private List<SessionEvent> queryRows(String sql, Object... params) {
        List<SessionEvent> out = new ArrayList<>();
        for (Map<String, Object> row : db.query(sql, params)) {
            out.add(toEvent(row));
        }
        return out;
    }

    private static SessionEvent toEvent(Map<String, Object> row) {
        String type = String.valueOf(row.get("type"));
        String dataText = String.valueOf(row.get("data"));
        ObjectNode data;
        try {
            JsonNode parsed = Json.parse(dataText);
            if (!parsed.isObject()) {
                throw new IllegalStateException("session event data 不是 JSON 对象：type=" + type);
            }
            data = (ObjectNode) parsed;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("session event data 解析失败：type=" + type, e);
        }
        return new SessionEvent(
                type,
                ((Number) row.get("seq")).intValue(),
                ((Number) row.get("ts_ms")).longValue(),
                ((Number) row.get("turn_no")).intValue(),
                ((Number) row.get("step_no")).intValue(),
                ((Number) row.get("ignorable")).intValue() != 0,
                row.get("surface_op") == null ? null : String.valueOf(row.get("surface_op")),
                decodeSeqs(row.get("source_seqs") == null ? null : String.valueOf(row.get("source_seqs"))),
                data);
    }

    private static String encodeSeqs(int[] seqs) {
        if (seqs == null || seqs.length == 0) return null;
        ArrayNode arr = Json.array();
        for (int s : seqs) arr.add(s);
        return arr.toString();
    }

    private static int[] decodeSeqs(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode n = Json.parse(text);
            if (!n.isArray() || n.isEmpty()) return null;
            int[] out = new int[n.size()];
            for (int i = 0; i < n.size(); i++) out[i] = n.get(i).asInt();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }
}
