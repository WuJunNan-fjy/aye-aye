package com.aye.ai.audit;

import com.aye.ai.permission.DestructiveTag;

import java.util.List;

/**
 * 单条审计记录（对应 DeepWork 的 {@code AuditRecord}）。
 */
public record AuditRecord(
        String id,
        String sessionId,
        String toolName,
        String inputSha256,
        AuditDecision decision,
        DecidedBy decidedBy,
        long startedAt,
        long durationMs,
        boolean success,
        String errorMessage,
        List<String> resolvedPaths,
        List<DestructiveTag> destructiveTags,
        String outputPreview
) {
    /** 决策来源 */
    public enum DecidedBy {
        SYSTEM_RULE, USER_MANUAL, USER, DEFAULT_RULE, UNKNOWN
    }
}
