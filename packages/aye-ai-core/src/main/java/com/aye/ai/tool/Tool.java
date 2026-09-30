package com.aye.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.DestructiveTag;
import com.aye.ai.permission.ToolCategory;

import java.util.List;

/**
 * 工具契约接口（对应 DeepWork 的 {@code DeepWorkToolDef}）。
 *
 * <p>业务侧（含 AI 核心内置工具、以及 app 层注入的 SSH/文件/服务器操作工具）统一实现此接口。
 * Zod schema 被替换为手写 JSON Schema（{@link JsonSchema}），运行时校验由
 * {@link JsonSchemaValidator} 承担，等价于 Zod 的 {@code safeParse}。
 */
public interface Tool {

    /** 工具名称（唯一标识，LLM 用此名称调用） */
    String name();

    /**
     * 工具操作类别（分类注册表的核心，<b>必填</b>）。
     *
     * <p>实现类必须覆盖此方法返回非空 {@link ToolCategory}；{@link ToolRegistry#register}
     * 会对返回值 null 抛异常、拒绝加载（fail-closed）。权限引擎据此自动决定放行/确认/拒绝，
     * 不再依赖硬编码工具名单。
     */
    default ToolCategory category() { return null; }

    /** 工具描述（LLM 据此判断何时调用） */
    String description();

    /** 输入参数的 JSON Schema（运行时校验 + 生成 LLM tools 数组） */
    JsonNode inputSchema();

    /** 执行函数（业务逻辑在此） */
    Object execute(JsonNode input, ToolContext ctx) throws Exception;

    /** 短描述（注入系统提示词的工具说明部分） */
    default String promptSnippet() { return null; }

    /** 使用规则（注入系统提示词的注意事项部分） */
    default String promptGuidelines() { return null; }

    /** 是否需要用户确认（规则引擎未命中时的兜底决策） */
    default boolean requiresConfirmation() { return false; }

    /** 超时时间（毫秒，默认 30s） */
    default long timeoutMs() { return 30_000L; }

    /**
     * 是否可与其它安全工具同批并行执行（参考 Claude Code {@code isConcurrencySafe}）。
     *
     * <p>主循环对一轮内的工具调用按此标志分区：<b>连续的安全工具并行执行</b>（结果仍按
     * 调用顺序回灌），非安全工具（写/执行/交互）串行执行，保证「写后读」等顺序语义。
     *
     * <p>默认仅 {@link ToolCategory#READ} 且无交互阻塞的工具安全。实现类必须自行保证
     * execute() 线程安全（无共享可变状态、或内部已加锁）；{@code ask_user} 这类会阻塞
     * 等待用户的工具必须覆盖为 false。
     */
    default boolean isConcurrencySafe() { return category() == ToolCategory.READ; }

    // ============================================================
    // 工具声明钩子（权限引擎 + 审计用，全部可选）
    // ============================================================

    /** 申报「本工具本次会读/写到哪些绝对路径」 */
    default List<String> resolveAffectedPaths(JsonNode input, ToolContext ctx) { return List.of(); }

    /** 申报「本工具本次执行的命令文本」（仅 bash/run_shell 需要） */
    default String resolveCommandText(JsonNode input) { return ""; }

    /** 申报「基于参数已经确定的破坏性标签」 */
    default List<DestructiveTag> computeDestructiveHints(JsonNode input, ToolContext ctx) { return List.of(); }
}
