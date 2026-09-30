package com.aye.server.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.audit.AuditDecision;
import com.aye.ai.audit.AuditLedger;
import com.aye.ai.audit.AuditRecord;
import com.aye.ai.audit.ExecutionResultForAudit;
import com.aye.ai.permission.DestructiveTag;
import com.aye.server.storage.AuditStore;
import com.aye.server.storage.HostStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite 版审计账本：把每个工具执行落库到 {@code audit_logs} 表。
 *
 * <p>替换 {@code initializeToolRuntime} 默认的内存版 {@code InMemoryAuditLedger}，
 * 让「日志中心」页有真实数据可查。字段映射：
 * <ul>
 *   <li>hostId 由工具入参的稳定 host_key 解析得到；command / arguments 从工具入参提取；</li>
 *   <li>status = SUCCESS / FAILED；approvalStatus = auto / rejected（由权限决策推导）；</li>
 *   <li>exit_code 从 run_ssh 输出的 {@code [exit=N]} 解析。</li>
 * </ul>
 */
public final class SqliteAuditLedger implements AuditLedger {

    /** run_ssh 输出里的退出码标记，如 {@code [exit=0]} */
    private static final Pattern EXIT_PATTERN = Pattern.compile("\\[exit=(\\d+)\\]");

    private static final int MAX_OUTPUT = 4000;

    private final AuditStore auditStore;
    private final HostStore hostStore;
    private final String executionMode;
    /**
     * 在途工具记录。工具并行执行后 recordStart/recordEnd 来自不同池线程，
     * markDecision 可能在主循环线程，故必须用并发 Map。
     */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String sessionId, String toolName, JsonNode input,
                           long startedAt, AuditDecision decision) {}

    public SqliteAuditLedger(AuditStore auditStore, HostStore hostStore, String executionMode) {
        this.auditStore = auditStore;
        this.hostStore = hostStore;
        this.executionMode = executionMode;
    }

    @Override
    public String recordStart(String sessionId, String toolName, String inputSha256, JsonNode input,
                              List<String> resolvedPaths, List<DestructiveTag> destructiveTags) {
        String recordId = UUID.randomUUID().toString();
        pending.put(recordId, new Pending(sessionId, toolName, input,
                System.currentTimeMillis(), AuditDecision.ALLOW));
        return recordId;
    }

    @Override
    public void markDecision(String recordId, AuditDecision decision, AuditRecord.DecidedBy decidedBy) {
        Pending p = pending.get(recordId);
        if (p == null) return;
        pending.put(recordId, new Pending(p.sessionId(), p.toolName(), p.input(), p.startedAt(), decision));
    }

    @Override
    public void recordEnd(String recordId, ExecutionResultForAudit result) {
        Pending p = pending.remove(recordId);
        if (p == null) return;

        boolean denied = isDenied(p.decision());
        long duration = result.durationMs() != null && result.durationMs() >= 0
                ? result.durationMs() : System.currentTimeMillis() - p.startedAt();
        long hostId = hostIdOf(p.input());

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("hostId", hostId == 0 ? null : hostId);
        log.put("hostName", hostNameOf(hostId));
        log.put("toolName", p.toolName());
        log.put("command", commandText(p.toolName(), p.input()));
        log.put("arguments", p.input() == null ? null : p.input().toString());
        log.put("status", denied || !result.success() ? "FAILED" : "SUCCESS");
        log.put("approvalStatus", denied ? "rejected" : "auto");
        log.put("executionMode", executionMode);
        log.put("duration", duration);
        log.put("output", clip(denied ? result.errorMessage() : result.outputPreview()));
        log.put("exitCode", exitCodeOf(result.outputPreview()));

        try {
            auditStore.save(log); // 写入失败不阻断主流程（AuditStore 由调用方兜底）
        } catch (RuntimeException ignored) {
            // 落库失败静默，不影响 Agent 主流程
        }
    }

    @Override
    public List<AuditRecord> listBySession(String sessionId) {
        return List.of(); // 历史由日志中心页走 /api/audit 查询，此处无需
    }

    private static boolean isDenied(AuditDecision d) {
        return d == AuditDecision.DENY
                || d == AuditDecision.CONFIRM_PENDING
                || d == AuditDecision.CONFIRM_REJECTED;
    }

    private long hostIdOf(JsonNode input) {
        if (input == null || !input.path("host_key").isTextual()) return 0;
        try {
            return hostStore.findOneByKey(input.path("host_key").asText()).get("id") instanceof Number n
                    ? n.longValue() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String hostNameOf(long hostId) {
        if (hostId == 0) return null;
        try {
            Map<String, Object> host = hostStore.findOne(hostId);
            Object name = host == null ? null : host.get("name");
            return name == null ? null : name.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String commandText(String toolName, JsonNode input) {
        if (input == null) return toolName;
        if ("run_ssh".equals(toolName)) {
            String c = input.path("command").asText("");
            return c.isBlank() ? toolName : c;
        }
        if (toolName.startsWith("sftp_")) {
            String path = input.path("path").asText("");
            return path.isBlank() ? toolName : toolName + " " + path;
        }
        return toolName;
    }

    private static Integer exitCodeOf(String outputPreview) {
        if (outputPreview == null) return null;
        Matcher m = EXIT_PATTERN.matcher(outputPreview);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String clip(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) : s;
    }
}
