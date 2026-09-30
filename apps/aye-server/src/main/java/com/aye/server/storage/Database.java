package com.aye.server.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 单连接封装（桌面单机场景，无连接池）。
 *
 * <p>所有访问 {@code synchronized} 串行化，避免 SQLite 并发写冲突。
 * 会话 / 消息 / 模型 / 设置四张表都在这里初始化。
 */
public final class Database {

    private final Connection conn;

    public Database(String dataDir) {
        File dir = new File(dataDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录：" + dataDir);
        }
        String dbPath = new File(dir, "aye.db").getAbsolutePath().replace('\\', '/');
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initSchema();
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开 SQLite 数据库：" + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id         TEXT PRIMARY KEY,
                        title      TEXT,
                        host_id    INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            ensureColumns(st, "sessions", Map.of(
                    "title", "TEXT",
                    "host_id", "INTEGER"));

            st.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL,
                        role       TEXT NOT NULL,
                        parts      TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, id)");

            // 旧版 models 表（模型实例制）已废弃：由 ModelProviderStore 首启迁移后 DROP，
            // 这里不再重建，避免每次启动都生成空表。

            // ---- 服务器操作相关表（翻译自旧 NestJS 的 database.ts） ----

            st.execute("""
                    CREATE TABLE IF NOT EXISTS hosts (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        host_key         TEXT,
                        name             TEXT NOT NULL,
                        address          TEXT NOT NULL,
                        port             INTEGER NOT NULL DEFAULT 22,
                        username         TEXT NOT NULL,
                        auth_type        TEXT NOT NULL DEFAULT 'PASSWORD',
                        credential_id    TEXT,
                        private_key_path TEXT,
                        description      TEXT,
                        sort_order       INTEGER NOT NULL DEFAULT 0,
                        created_at       TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            ensureColumns(st, "hosts", Map.of(
                    "host_key", "TEXT",
                    "port", "INTEGER NOT NULL DEFAULT 22",
                    "auth_type", "TEXT NOT NULL DEFAULT 'PASSWORD'",
                    "credential_id", "TEXT",
                    "private_key_path", "TEXT",
                    "description", "TEXT",
                    "sort_order", "INTEGER NOT NULL DEFAULT 0"));
            st.execute("UPDATE hosts SET host_key = 'host_' || lower(hex(randomblob(16))) "
                    + "WHERE host_key IS NULL OR trim(host_key) = ''");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_hosts_host_key ON hosts(host_key)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS audit_logs (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        host_id         INTEGER,
                        host_name       TEXT,
                        tool_name       TEXT NOT NULL DEFAULT 'execute_command',
                        command         TEXT NOT NULL,
                        commands        TEXT,
                        arguments       TEXT,
                        status          TEXT NOT NULL,
                        approval_status TEXT NOT NULL DEFAULT 'auto',
                        execution_mode  TEXT,
                        duration        INTEGER,
                        output          TEXT,
                        exit_code       INTEGER,
                        created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            // 旧版本库缺列时补齐（ALTER TABLE ADD COLUMN；created_at 不迁移默认表达式，保持可空）
            ensureColumns(st, "audit_logs", Map.ofEntries(
                    Map.entry("host_id", "INTEGER"),
                    Map.entry("host_name", "TEXT"),
                    Map.entry("tool_name", "TEXT NOT NULL DEFAULT 'execute_command'"),
                    Map.entry("command", "TEXT NOT NULL DEFAULT ''"),
                    Map.entry("commands", "TEXT"),
                    Map.entry("arguments", "TEXT"),
                    Map.entry("status", "TEXT NOT NULL DEFAULT 'success'"),
                    Map.entry("approval_status", "TEXT NOT NULL DEFAULT 'auto'"),
                    Map.entry("execution_mode", "TEXT"),
                    Map.entry("duration", "INTEGER"),
                    Map.entry("output", "TEXT"),
                    Map.entry("exit_code", "INTEGER"),
                    Map.entry("created_at", "TEXT")));
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_tool_name ON audit_logs(tool_name)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_status ON audit_logs(status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_host_id ON audit_logs(host_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_logs(created_at)");

            // ---- [已退役 2026-09-22 精简批8] Agent 运行追踪表 ----
            // 旧 TraceStore/SqliteAgentTraceRecorder 管线已删，追踪读投影统一收口到
            // /api/observability/*（session_events 投影）。DDL 保留仅为兼容老用户库，不再写入。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_trace_log (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id    TEXT NOT NULL,
                        step          INTEGER,
                        llm_latency   INTEGER,
                        tool_names    TEXT,
                        tool_duration INTEGER,
                        token_in      INTEGER,
                        token_out     INTEGER,
                        compacted     INTEGER NOT NULL DEFAULT 0,
                        stop_reason   TEXT,
                        created_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            // 旧版本库缺列时补齐
            ensureColumns(st, "agent_trace_log", Map.ofEntries(
                    Map.entry("session_id", "TEXT NOT NULL DEFAULT ''"),
                    Map.entry("step", "INTEGER"),
                    Map.entry("llm_latency", "INTEGER"),
                    Map.entry("tool_names", "TEXT"),
                    Map.entry("tool_duration", "INTEGER"),
                    Map.entry("token_in", "INTEGER"),
                    Map.entry("token_out", "INTEGER"),
                    Map.entry("compacted", "INTEGER NOT NULL DEFAULT 0"),
                    Map.entry("stop_reason", "TEXT")));
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_session ON agent_trace_log(session_id, id)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS app_settings (
                        key        TEXT PRIMARY KEY,
                        value      TEXT,
                        updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");

            // ---- [已退役 2026-09-22 精简批6] 预置快捷指令表 ----
            // 快捷指令功能已整体下线（QuickCommandStore/QuickCommandController/list+run 工具删除），
            // 按精简规约不 drop 老用户库中的既有表；以下 CREATE 对老库无操作，新库仍会建空表（零危害）。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS quick_commands (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        name        TEXT NOT NULL,
                        command     TEXT NOT NULL,
                        description TEXT,
                        host_id     INTEGER,
                        is_global   INTEGER NOT NULL DEFAULT 0,
                        created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            ensureColumns(st, "quick_commands", Map.of(
                    "description", "TEXT",
                    "host_id", "INTEGER",
                    "is_global", "INTEGER NOT NULL DEFAULT 0",
                    "created_at", "TEXT"));

            // ---- [已退役 2026-09-22 精简批5] 远程文件回滚备份记录表（LOCAL 快照 / REMOTE tar 包） ----
            // 备份/回滚功能已整体下线（BackupService/BackupController/RemoteBackupStore 删除），
            // 按精简规约不 drop 老用户库中的既有表；以下 CREATE 对老库无操作，新库仍会建空表（零危害）。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS backups (
                        id          TEXT PRIMARY KEY,
                        session_id  TEXT,
                        host_id     INTEGER,
                        host_name   TEXT,
                        kind        TEXT NOT NULL,
                        paths       TEXT,
                        tag         TEXT,
                        detail      TEXT,
                        content_hash TEXT,
                        metadata    TEXT,
                        status      TEXT NOT NULL DEFAULT 'ok',
                        size_bytes  INTEGER,
                        created_at  TEXT NOT NULL
                    )""");
            ensureColumns(st, "backups", Map.ofEntries(
                    Map.entry("session_id", "TEXT"),
                    Map.entry("host_id", "INTEGER"),
                    Map.entry("host_name", "TEXT"),
                    Map.entry("kind", "TEXT"),
                    Map.entry("paths", "TEXT"),
                    Map.entry("tag", "TEXT"),
                    Map.entry("detail", "TEXT"),
                    Map.entry("content_hash", "TEXT"),
                    Map.entry("metadata", "TEXT"),
                    Map.entry("status", "TEXT NOT NULL DEFAULT 'ok'"),
                    Map.entry("size_bytes", "INTEGER"),
                    Map.entry("created_at", "TEXT")));
            st.execute("CREATE INDEX IF NOT EXISTS idx_backups_session ON backups(session_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_backups_host ON backups(host_id)");

            // ---- SSH 主机密钥 TOFU（P0-4：known_hosts 持久化，抵御中间人） ----
            st.execute("""
                    CREATE TABLE IF NOT EXISTS ssh_known_hosts (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        host_key    TEXT NOT NULL,
                        address     TEXT NOT NULL,
                        port        INTEGER NOT NULL DEFAULT 22,
                        fingerprint TEXT NOT NULL,
                        trust_type  TEXT NOT NULL DEFAULT 'tofu',
                        created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_known_hosts_addr ON ssh_known_hosts(address, port)");

            // ---- [已退役 2026-09-22 精简批4] 长期记忆表 ----
            // 记忆功能已整体下线（MemoryStore/MemoryController 删除），按精简规约不 drop
            // 老用户库中的既有表；以下 CREATE 对老库无操作，新库仍会建空表（零危害）。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        scope       TEXT NOT NULL,
                        content     TEXT NOT NULL,
                        source      TEXT NOT NULL DEFAULT 'manual',
                        created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
                        updated_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope)");

            // ---- 事件溯源：会话事件日志（唯一事实源）+ turn 状态投影 ----
            // 行与 SessionEvent 1:1 映射；(session_id, seq) 为主键，seq 会话内连续。
            // data 为事件 payload JSON；source_seqs 为 int 数组 JSON（可空）。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS session_events (
                        session_id  TEXT NOT NULL,
                        seq         INTEGER NOT NULL,
                        type        TEXT NOT NULL,
                        ignorable   INTEGER NOT NULL DEFAULT 0,
                        surface_op  TEXT,
                        source_seqs TEXT,
                        turn_no     INTEGER NOT NULL DEFAULT -1,
                        step_no     INTEGER NOT NULL DEFAULT -1,
                        data        TEXT NOT NULL,
                        ts_ms       INTEGER NOT NULL,
                        PRIMARY KEY (session_id, seq)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_session_time "
                    + "ON session_events(session_id, ts_ms)");
            // 观测中心 activity 按 type IN(...) + ts_ms DESC 扫描近期窗口，
            // 没有该索引则每次翻页都全表扫 chunk 大海表。
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_type_time "
                    + "ON session_events(type, ts_ms)");

            // turn 投影表：由事件持久化订阅者在刷盘时顺带维护（turn/start→running，turn/end→done）。
            // steps/tool_calls/interactions/errors/tokens_* 为事件流增量物化，
            // turns 列表直接读列，避免每次翻页对 session_events 全表 GROUP BY + json_extract。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS session_turns (
                        session_id   TEXT NOT NULL,
                        turn_no      INTEGER NOT NULL,
                        status       TEXT NOT NULL,
                        model_id     TEXT,
                        mode         TEXT,
                        reason       TEXT,
                        started_ms   INTEGER NOT NULL,
                        ended_ms     INTEGER,
                        steps        INTEGER NOT NULL DEFAULT 0,
                        tool_calls   INTEGER NOT NULL DEFAULT 0,
                        interactions INTEGER NOT NULL DEFAULT 0,
                        errors       INTEGER NOT NULL DEFAULT 0,
                        tokens_in    INTEGER NOT NULL DEFAULT 0,
                        tokens_out   INTEGER NOT NULL DEFAULT 0,
                        tokens_total INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (session_id, turn_no)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_turns_session ON session_turns(session_id, turn_no)");
            // 旧版本库补列（老数据已清空，历史行取默认 0）。
            ensureColumns(st, "session_turns", Map.of(
                    "steps", "INTEGER NOT NULL DEFAULT 0",
                    "tool_calls", "INTEGER NOT NULL DEFAULT 0",
                    "interactions", "INTEGER NOT NULL DEFAULT 0",
                    "errors", "INTEGER NOT NULL DEFAULT 0",
                    "tokens_in", "INTEGER NOT NULL DEFAULT 0",
                    "tokens_out", "INTEGER NOT NULL DEFAULT 0",
                    "tokens_total", "INTEGER NOT NULL DEFAULT 0"));

            // ---- 小白一键部署：工作区登记 + 部署账本 ----
            st.execute("""
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_path TEXT NOT NULL UNIQUE,
                        title TEXT NOT NULL,
                        source TEXT NOT NULL,
                        git_url TEXT,
                        created_at INTEGER NOT NULL,
                        last_used_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS deploy_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        workspace_id INTEGER NOT NULL,
                        session_id TEXT NOT NULL,
                        host_id INTEGER NOT NULL,
                        current_stage TEXT NOT NULL,
                        stages_json TEXT NOT NULL,
                        plan_json TEXT,
                        artifact_json TEXT,
                        remote_app_dir TEXT,
                        remote_pid INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_deploy_runs_session ON deploy_runs(session_id)");
            // 会话 → 当前工作区绑定（持久化：刷新/重启后回填，不再只存内存）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS session_workspaces (
                        session_id TEXT PRIMARY KEY,
                        workspace_id INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
        }
    }

    /**
     * 轻量 schema 迁移：旧版本库里已存在但缺列的表逐列补齐（PRAGMA table_info + ALTER TABLE ADD COLUMN）。
     *
     * <p>背景：{@code CREATE TABLE IF NOT EXISTS} 对已有旧表不做任何变更，旧库一旦缺列，
     * 后续 {@code CREATE INDEX ... ON 表(列)} 或查询就会报 "no such column"。注意 SQLite 的
     * ADD COLUMN 不允许带括号表达式默认值（如 {@code datetime('now','localtime')}），
     * 因此迁移定义里的时间列一律声明为可空 {@code TEXT}。
     */
    @SuppressWarnings("SameParameterValue")
    private void ensureColumns(Statement st, String table, Map<String, String> columns) throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                existing.add(rs.getString("name"));
            }
        }
        for (Map.Entry<String, String> e : columns.entrySet()) {
            if (!existing.contains(e.getKey())) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + e.getKey() + " " + e.getValue());
            }
        }
    }

    /** 查询，返回行列表（每行是列名 → 值的 LinkedHashMap） */
    public synchronized List<Map<String, Object>> query(String sql, Object... params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 查询失败：" + e.getMessage(), e);
        }
        return rows;
    }

    /** 执行写操作，返回受影响行数 */
    public synchronized int update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 更新失败：" + e.getMessage(), e);
        }
    }

    /** 上一条 INSERT 语句生成的自增主键（SQLite 的 last_insert_rowid） */
    public synchronized long lastInsertRowId() {
        List<Map<String, Object>> rows = query("SELECT last_insert_rowid() AS id");
        Object v = rows.isEmpty() ? null : rows.get(0).get("id");
        if (v instanceof Number n) return n.longValue();
        return v == null ? 0 : Long.parseLong(v.toString());
    }

    /** 关闭连接（测试/工具用；生产单例随进程退出）。 */
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败静默
        }
    }

    /** 在单个连接上跑一个事务（桌面单机场景，串行化已由 synchronized 保证） */
    public synchronized void transaction(Runnable work) {
        try {
            conn.setAutoCommit(false);
            work.run();
            conn.commit();
        } catch (Exception e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new IllegalStateException("事务执行失败：" + e.getMessage(), e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
