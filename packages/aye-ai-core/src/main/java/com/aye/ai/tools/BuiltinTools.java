package com.aye.ai.tools;

import com.aye.ai.audit.AuditLedger;
import com.aye.ai.audit.InMemoryAuditLedger;
import com.aye.ai.permission.TrustLevel;
import com.aye.ai.tool.DefaultFileMutationQueue;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.tool.ToolContext.UserConfirmCallback;
import com.aye.ai.tool.ToolRegistry;

import java.util.function.BooleanSupplier;

/**
 * 工具系统统一入口（对应 DeepWork 的 {@code tools/index.ts}）。
 *
 * <p>负责：
 * <ol>
 *   <li>内置工具表（加工具在此追加，顺序影响发给 LLM 的 definition 顺序）</li>
 *   <li>{@link #createToolRegistry()} 工厂 —— 注册全部内置工具</li>
 *   <li>{@link #initializeToolRuntime} 工厂 —— 每个新会话调用一次，返回注入了
 *       审计账本 / 文件串行化队列的 ToolContext</li>
 * </ol>
 */
public final class BuiltinTools {

    private BuiltinTools() {}

    /** 创建工具注册表并注册全部内置工具。 */
    public static ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(AskUserTool.INSTANCE);      // 主动询问用户
        return registry;
    }

    /**
     * 骨架初始化：每个新会话调用一次，返回填充好骨架扩展字段的 ToolContext。
     *
     * @param registry   ToolRegistry（必填）
     * @param sessionId  会话 ID（必填，审计日志按此分组、前端按此查询）
     * @param cwd        工作区绝对路径（必填，工具层 resolvePath 从此开始）
     */
    public static ToolContext initializeToolRuntime(ToolRegistry registry, String sessionId, String cwd) {
        return initializeToolRuntime(registry, sessionId, cwd, null, null, null);
    }

    /**
     * 骨架初始化（全参数版）。
     *
     * @param trustLevel         用户信任级别，默认 {@link TrustLevel#STANDARD}
     * @param aborted            可选：会话级中止信号（用户点"取消"时触发）
     * @param waitForUserConfirm 可选：权限引擎需要用户确认时的回调；未接入 loop 时工具层走「保守拒绝」兜底
     */
    public static ToolContext initializeToolRuntime(
            ToolRegistry registry,
            String sessionId,
            String cwd,
            TrustLevel trustLevel,
            BooleanSupplier aborted,
            UserConfirmCallback waitForUserConfirm) {
        return initializeToolRuntime(registry, sessionId, cwd, trustLevel, aborted, waitForUserConfirm, null);
    }

    /**
     * 骨架初始化（全参数版 + 自定义审计账本）。
     *
     * <p>默认用内存版 {@link InMemoryAuditLedger}；需要把审计持久化到 SQLite 等
     * 存储时（如服务端运维助手），传入自定义 {@link AuditLedger} 实现即可。
     */
    public static ToolContext initializeToolRuntime(
            ToolRegistry registry,
            String sessionId,
            String cwd,
            TrustLevel trustLevel,
            BooleanSupplier aborted,
            UserConfirmCallback waitForUserConfirm,
            AuditLedger audit) {

        return new ToolContext()
                .sessionId(sessionId)
                .conversationId(sessionId)
                .cwd(cwd)
                .trustLevel(trustLevel != null ? trustLevel : TrustLevel.STANDARD)
                .audit(audit != null ? audit : new InMemoryAuditLedger())
                .fileMutationQueue(new DefaultFileMutationQueue())
                .waitForUserConfirm(waitForUserConfirm)
                .aborted(aborted);
    }
}
