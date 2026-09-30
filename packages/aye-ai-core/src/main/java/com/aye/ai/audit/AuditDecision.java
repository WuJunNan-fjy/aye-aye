package com.aye.ai.audit;

/** 权限引擎的最终决策（对应 DeepWork 的 {@code AuditDecision}）。 */
public enum AuditDecision {
    ALLOW,
    DENY,
    CONFIRM_PENDING,
    CONFIRM_ACCEPTED,
    CONFIRM_REJECTED
}
