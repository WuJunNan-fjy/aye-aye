package com.aye.server.deploy;

import com.aye.server.storage.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区存储：本机项目目录的持久化登记 + 会话绑定。
 *
 * <p>canonical 路径（toRealPath）是唯一去重口径；重复登记返回既有记录并刷新 last_used_at。
 * 会话绑定（sessionId → workspaceId）落 session_workspaces 表并在内存缓存：
 * ChatService 在 turn 开始时绑定，run_local_async 等工具据此取工作区根；
 * 刷新/重启后 boundWorkspace 懒加载回填。
 */
public final class WorkspaceStore {

    /** 工作区记录（行数据透传，字段为蛇形命名）。 */
    public static final class Workspace {
        private final Map<String, Object> row;

        Workspace(Map<String, Object> row) { this.row = row; }

        public long id() { return ((Number) row.get("id")).longValue(); }
        public String canonicalPath() { return String.valueOf(row.get("canonical_path")); }
        public String title() { return String.valueOf(row.get("title")); }
        public String source() { return String.valueOf(row.get("source")); }
        public String gitUrl() {
            return row.get("git_url") == null ? null : String.valueOf(row.get("git_url"));
        }
        public Map<String, Object> row() { return row; }
    }

    private final Database db;
    private final ConcurrentHashMap<String, Long> sessionBinding = new ConcurrentHashMap<>();

    public WorkspaceStore(Database db) {
        this.db = db;
    }

    /** 登记工作区；路径不存在/非目录抛 IllegalArgumentException；重复路径复用。 */
    public Workspace create(String rawPath, String title, String source, String gitUrl) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("工作区路径不能为空");
        }
        Path p;
        try {
            p = Path.of(rawPath).toAbsolutePath().normalize().toRealPath();
        } catch (Exception e) {
            throw new IllegalArgumentException("工作区目录不存在或无法访问：" + rawPath, e);
        }
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("工作区路径不是目录：" + p);
        }
        String canonical = p.toString();
        List<Map<String, Object>> existing =
                db.query("SELECT * FROM workspaces WHERE canonical_path = ?", canonical);
        long now = System.currentTimeMillis();
        if (!existing.isEmpty()) {
            long id = ((Number) existing.get(0).get("id")).longValue();
            db.update("UPDATE workspaces SET last_used_at = ? WHERE id = ?", now, id);
            return new Workspace(
                    db.query("SELECT * FROM workspaces WHERE id = ?", id).get(0));
        }
        String resolvedTitle = (title == null || title.isBlank())
                ? p.getFileName().toString() : title;
        String resolvedSource = (source == null || source.isBlank()) ? "local" : source;
        db.update("""
                        INSERT INTO workspaces
                            (canonical_path, title, source, git_url, created_at, last_used_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                canonical, resolvedTitle, resolvedSource, gitUrl, now, now);
        long id = db.lastInsertRowId();
        return new Workspace(
                db.query("SELECT * FROM workspaces WHERE id = ?", id).get(0));
    }

    public List<Workspace> list() {
        return db.query("SELECT * FROM workspaces ORDER BY last_used_at DESC")
                .stream().map(Workspace::new).toList();
    }

    public Workspace get(long id) {
        List<Map<String, Object>> rows =
                db.query("SELECT * FROM workspaces WHERE id = ?", id);
        return rows.isEmpty() ? null : new Workspace(rows.get(0));
    }

    public void rename(long id, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        db.update("UPDATE workspaces SET title = ? WHERE id = ?", title, id);
    }

    /** 删除登记，不碰磁盘文件。 */
    public boolean delete(long id) {
        return db.update("DELETE FROM workspaces WHERE id = ?", id) > 0;
    }

    /** 绑定会话到工作区（ChatService turn 开始时调用）；upsert 持久化。 */
    public void bindSession(String sessionId, long workspaceId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (get(workspaceId) == null) {
            throw new IllegalArgumentException("工作区不存在：" + workspaceId);
        }
        long now = System.currentTimeMillis();
        db.update("""
                INSERT INTO session_workspaces (session_id, workspace_id, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    workspace_id = excluded.workspace_id,
                    updated_at = excluded.updated_at""",
                sessionId, workspaceId, now);
        sessionBinding.put(sessionId, workspaceId);
    }

    /** 取会话绑定的工作区；未绑定（或工作区已被删除）返回 null，结果回填内存缓存。 */
    public Workspace boundWorkspace(String sessionId) {
        if (sessionId == null) return null;
        Long id = sessionBinding.get(sessionId);
        if (id == null) {
            List<Map<String, Object>> rows = db.query(
                    "SELECT workspace_id FROM session_workspaces WHERE session_id = ?", sessionId);
            if (rows.isEmpty()) return null;
            id = ((Number) rows.get(0).get("workspace_id")).longValue();
            sessionBinding.put(sessionId, id);
        }
        Workspace ws = get(id);
        if (ws == null) {
            // 工作区登记已被删除：绑定失效，顺手清掉持久化行与缓存
            unbindSession(sessionId);
            return null;
        }
        return ws;
    }

    public void unbindSession(String sessionId) {
        if (sessionId == null) return;
        sessionBinding.remove(sessionId);
        db.update("DELETE FROM session_workspaces WHERE session_id = ?", sessionId);
    }
}
