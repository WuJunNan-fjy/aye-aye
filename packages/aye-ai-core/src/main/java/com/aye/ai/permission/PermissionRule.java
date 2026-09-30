package com.aye.ai.permission;

/**
 * 权限规则（对应 DeepWork 的 {@code PermissionRule}）。
 */
public interface PermissionRule {

    /** 调试用：规则名，审计日志可选记录 */
    String name();

    /** 条件匹配：true = 本规则适用；false = 跳下一条 */
    boolean matches(PermissionContext ctx) throws Exception;

    /** 决策函数：返回非 null = 决策生效；返回 null = 本规则条件匹配但不拍板，继续下一条 */
    PermissionDecision decide(PermissionContext ctx) throws Exception;
}
