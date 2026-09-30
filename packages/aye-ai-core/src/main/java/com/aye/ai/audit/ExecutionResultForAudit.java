package com.aye.ai.audit;

/**
 * 审计执行结束时的结果结构（对应 DeepWork 的 {@code ExecutionResultForAudit}）。
 */
public record ExecutionResultForAudit(
        boolean success,
        String errorMessage,
        String outputPreview,
        Long durationMs
) {
    public static ExecutionResultForAudit ok(String outputPreview) {
        return new ExecutionResultForAudit(true, null, outputPreview, null);
    }

    public static ExecutionResultForAudit failed(String errorMessage) {
        return new ExecutionResultForAudit(false, errorMessage, "", null);
    }
}
