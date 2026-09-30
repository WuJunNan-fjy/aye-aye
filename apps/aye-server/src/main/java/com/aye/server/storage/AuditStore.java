package com.aye.server.storage;

import com.aye.ai.util.Json;

import java.util.Map;

/**
 * 审计日志存储（SQLite 的 {@code audit_logs} 表）——只写留痕。
 *
 * <p>保存命令执行留痕，作为安全审计事实随库持久化；写入失败不阻断主流程
 * （由调用方兜底）。日志/追踪类读投影已统一收口到事件日志投影
 * （{@code /api/observability/*}，事实源为 session_events），本类不再提供查询接口。
 */
public final class AuditStore {

    private final Database db;

    public AuditStore(Database db) {
        this.db = db;
    }

    /** 记录一条审计日志。arguments 可为 Map 或字符串（非 JSON 字符串时按原文存） */
    public void save(Map<String, Object> log) {
        Object args = log.get("arguments");
        String argsStr = null;
        if (args != null) {
            argsStr = args instanceof String s ? s : Json.stringify(args);
        }
        db.update("""
                        INSERT INTO audit_logs
                          (host_id, host_name, tool_name, command, commands, arguments, status,
                           approval_status, execution_mode, duration, output, exit_code, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now', 'localtime'))""",
                log.get("hostId"),
                log.get("hostName"),
                log.containsKey("toolName") && log.get("toolName") != null ? log.get("toolName") : "execute_command",
                log.get("command"),
                log.get("command"), // commands 字段兼容旧数据
                argsStr,
                log.get("status"),
                log.containsKey("approvalStatus") && log.get("approvalStatus") != null ? log.get("approvalStatus") : "auto",
                log.get("executionMode"),
                log.get("duration"),
                log.get("output"),
                log.get("exitCode"));
    }
}
