package com.aye.ai.permission;

import java.util.List;

/**
 * 权限决策结果（对应 DeepWork 的 {@code PermissionDecision} 判别联合）。
 */
public sealed interface PermissionDecision {

    /** 放行 */
    record Allow() implements PermissionDecision {}

    /** 拒绝（reason 直接作为 tool error 返回给 LLM） */
    record Deny(String reason) implements PermissionDecision {}

    /**
     * 需要用户确认。
     *
     * @param message            确认弹窗顶部的说明文案
     * @param tags               命中的破坏性标签（前端按类型显示不同颜色/图标）
     * @param persistentAllowKey 「始终允许」模式的匹配 key（null 表示不显示该选项）
     */
    record Confirm(String message, List<DestructiveTag> tags, String persistentAllowKey)
            implements PermissionDecision {}
}
