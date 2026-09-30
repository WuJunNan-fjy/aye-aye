package com.aye.server.storage;

import com.aye.ai.message.UIMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话 + 消息存储（SQLite 的 {@code sessions} / {@code messages} 表）。
 *
 * <p>消息的 {@code parts} 以 JSON 文本存（由 {@code MessageCodec} 编解码），
 * 角色为 user / assistant / tool / system。
 */
public final class SessionStore {

    private final Database db;

    public SessionStore(Database db) {
        this.db = db;
    }

    /** 建会话（幂等：已存在则只刷新 updated_at）。title 截断到 80 字符避免列表显示过长。 */
    public void createSession(String id, String title, Long hostId) {
        long now = System.currentTimeMillis();
        String safeTitle = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80) + "…";
        List<Map<String, Object>> existing = db.query("SELECT id FROM sessions WHERE id = ?", id);
        if (existing.isEmpty()) {
            db.update("INSERT INTO sessions (id, title, host_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    id, safeTitle, hostId, now, now);
        } else {
            // 已存在会话不覆盖标题，只刷 updated_at
            db.update("UPDATE sessions SET updated_at = ? WHERE id = ?", now, id);
        }
    }

    /** 追加一条消息 */
    public void appendMessage(String sessionId, UIMessage msg) {
        db.update("INSERT INTO messages (session_id, role, parts, created_at) VALUES (?, ?, ?, ?)",
                sessionId, msg.role(), MessageCodec.serializeParts(msg.parts()), System.currentTimeMillis());
    }

    /** 读取会话全部消息（按 id 升序 = 时间顺序） */
    public List<UIMessage> getMessages(String sessionId) {
        List<UIMessage> out = new ArrayList<>();
        for (Map<String, Object> row : db.query(
                "SELECT role, parts FROM messages WHERE session_id = ? ORDER BY id ASC", sessionId)) {
            String role = String.valueOf(row.get("role"));
            String partsJson = String.valueOf(row.get("parts"));
            out.add(new UIMessage(role, MessageCodec.parseParts(partsJson)));
        }
        return out;
    }

    /** 会话列表（按更新时间倒序） */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.query(
                "SELECT id, title, host_id, created_at, updated_at FROM sessions ORDER BY updated_at DESC")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("title", row.get("title"));
            m.put("hostId", row.get("host_id"));
            m.put("createdAt", row.get("created_at"));
            m.put("updatedAt", row.get("updated_at"));
            out.add(m);
        }
        return out;
    }

    /** 清空某会话的消息 */
    public void clearMessages(String sessionId) {
        db.update("DELETE FROM messages WHERE session_id = ?", sessionId);
    }

    /** 删除会话及其消息 */
    public void deleteSession(String sessionId) {
        db.update("DELETE FROM messages WHERE session_id = ?", sessionId);
        db.update("DELETE FROM sessions WHERE id = ?", sessionId);
    }
}
