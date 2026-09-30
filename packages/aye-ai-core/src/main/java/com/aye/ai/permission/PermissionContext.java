package com.aye.ai.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 喂给权限引擎的决策上下文（对应 DeepWork 的 {@code PermissionContext}）。
 */
public record PermissionContext(
        String toolName,
        JsonNode input,
        TrustLevel trustLevel,
        List<String> resolvedPaths,
        List<DestructiveTag> destructiveHints,
        String commandText,
        Boolean toolRequiresConfirmationDefault,
        String cwdSafe,
        /** 执行模式：readonly / write_confirm / all_confirm（前端传入，缺省 null） */
        String executionMode,
        /** 工具操作类别（分类注册表来源，规则据此决策，取代硬编码名单） */
        ToolCategory category
) {

    /** 工具是否会对文件系统或运行环境产生"写/执行/破坏"影响（即非只读）。 */
    public boolean isWriteOrExecute() {
        return category == ToolCategory.WRITE
                || category == ToolCategory.EXECUTE
                || category == ToolCategory.DESTRUCTIVE;
    }
}
