package com.aye.ai.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.DestructiveTag;

import java.util.List;

/**
 * 审计账本接口（对应 DeepWork 的 {@code AuditLedger}）。
 */
public interface AuditLedger {

    /** 无入参版本的 recordStart（兼容仅关心路径/哈希的调用方） */
    default String recordStart(String sessionId, String toolName, String inputSha256,
                               List<String> resolvedPaths, List<DestructiveTag> destructiveTags) {
        return recordStart(sessionId, toolName, inputSha256, null, resolvedPaths, destructiveTags);
    }

    /** 执行开始前调用，写入「开始」状态，返回 recordId；input 为解析后的工具入参 */
    String recordStart(String sessionId, String toolName, String inputSha256, JsonNode input,
                       List<String> resolvedPaths, List<DestructiveTag> destructiveTags);

    /** 权限决策后调用，更新 decision + decidedBy */
    void markDecision(String recordId, AuditDecision decision, AuditRecord.DecidedBy decidedBy);

    /** 执行结束后调用，填 durationMs / success / errorMessage / outputPreview */
    void recordEnd(String recordId, ExecutionResultForAudit result);

    /** 按会话列历史（最新的在前） */
    List<AuditRecord> listBySession(String sessionId);
}
