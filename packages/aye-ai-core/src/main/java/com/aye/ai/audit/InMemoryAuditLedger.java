package com.aye.ai.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.DestructiveTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版审计账本（对应 DeepWork 的 {@code InMemoryAuditLedger}）。
 *
 * <p>进程重启即丢；未来替换为 SQLite 版本时调用方零改动（接口已抽象）。
 *
 * <p>线程安全：工具并行执行后 recordStart/markDecision/recordEnd 可能来自不同线程，
 * 故 records 使用 ConcurrentHashMap、MutableRecord.record 为 volatile、会话索引列表
 * 使用同步 List。
 */
public class InMemoryAuditLedger implements AuditLedger {

    private final Map<String, MutableRecord> records = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionIndex = new ConcurrentHashMap<>();

    private static final class MutableRecord {
        volatile AuditRecord record;
    }

    @Override
    public String recordStart(String sessionId, String toolName, String inputSha256, JsonNode input,
                              List<String> resolvedPaths, List<DestructiveTag> destructiveTags) {
        String recordId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        AuditRecord record = new AuditRecord(
                recordId, sessionId, toolName, inputSha256,
                AuditDecision.ALLOW, AuditRecord.DecidedBy.UNKNOWN, now, -1, true,
                null,
                new ArrayList<>(resolvedPaths),
                new ArrayList<>(destructiveTags),
                "");

        MutableRecord m = new MutableRecord();
        m.record = record;
        records.put(recordId, m);
        sessionIndex.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>())).add(recordId);
        return recordId;
    }

    @Override
    public void markDecision(String recordId, AuditDecision decision, AuditRecord.DecidedBy decidedBy) {
        MutableRecord m = records.get(recordId);
        if (m == null) return;
        AuditRecord rec = m.record;
        AuditRecord updated = new AuditRecord(rec.id(), rec.sessionId(), rec.toolName(), rec.inputSha256(),
                decision, decidedBy, rec.startedAt(), rec.durationMs(), rec.success(), rec.errorMessage(),
                rec.resolvedPaths(), rec.destructiveTags(), rec.outputPreview());
        if (decision == AuditDecision.DENY || decision == AuditDecision.CONFIRM_REJECTED) {
            long duration = rec.durationMs() < 0 ? System.currentTimeMillis() - rec.startedAt() : rec.durationMs();
            updated = new AuditRecord(updated.id(), updated.sessionId(), updated.toolName(), updated.inputSha256(),
                    updated.decision(), updated.decidedBy(), updated.startedAt(), duration, true,
                    updated.errorMessage(), updated.resolvedPaths(), updated.destructiveTags(), updated.outputPreview());
        }
        m.record = updated;
    }

    @Override
    public void recordEnd(String recordId, ExecutionResultForAudit result) {
        MutableRecord m = records.get(recordId);
        if (m == null) return;
        AuditRecord rec = m.record;

        long duration = rec.durationMs();
        if (result.durationMs() != null && result.durationMs() >= 0) {
            duration = result.durationMs();
        } else if (duration < 0) {
            duration = System.currentTimeMillis() - rec.startedAt();
        }

        String errorMessage = rec.errorMessage();
        if (!result.success() && result.errorMessage() != null) {
            errorMessage = result.errorMessage();
        }

        String preview = result.outputPreview() == null ? "" : result.outputPreview();
        if (preview.length() > 500) preview = preview.substring(0, 500);

        m.record = new AuditRecord(rec.id(), rec.sessionId(), rec.toolName(), rec.inputSha256(),
                rec.decision(), rec.decidedBy(), rec.startedAt(), duration, result.success(),
                errorMessage, rec.resolvedPaths(), rec.destructiveTags(), preview);
    }

    @Override
    public List<AuditRecord> listBySession(String sessionId) {
        List<String> ids = sessionIndex.get(sessionId);
        if (ids == null) return List.of();
        List<String> snapshot;
        synchronized (ids) { // synchronizedList 迭代需持锁
            snapshot = new ArrayList<>(ids);
        }
        List<AuditRecord> out = new ArrayList<>();
        for (String id : snapshot) {
            MutableRecord m = records.get(id);
            if (m != null) out.add(m.record);
        }
        out.sort(Comparator.comparingLong(AuditRecord::startedAt).reversed());
        return out;
    }

    /** 测试辅助：清空账本 */
    public void resetForTest() {
        records.clear();
        sessionIndex.clear();
    }
}
