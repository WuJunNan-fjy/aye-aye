package com.aye.ai.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 事件（判别联合）。
 *
 * <p>对应 DeepWork 里 shared 包的 {@code AgentEvent} 判别联合类型：
 * thinking / text-delta / tool-call / tool-result / error / done。
 *
 * <p>Java 用 sealed interface + record 表达判别联合，编译期即可穷举所有分支，
 * 比 TypeScript 的 discriminated union 更严格（漏处理一个分支会编译报错）。
 */
public sealed interface AgentEvent
        permits AgentEvent.ContextInjectedEvent,
                AgentEvent.ThinkingEvent,
                AgentEvent.TextDeltaEvent,
                AgentEvent.ToolCallEvent,
                AgentEvent.ToolResultEvent,
                AgentEvent.ErrorEvent,
                AgentEvent.DoneEvent,
                AgentEvent.ConfirmationRequestEvent,
                AgentEvent.AskUserEvent {

    /**
     * 上下文注入：本轮送入 LLM 的 system/user 消息之外，额外"被读取并纳入思考"的
     * 外部材料（项目说明文档、子模块 system prompt、技能目录、工作目录等）。
     * 仅用于前端在时间线上展示「上下文注入 · AGENTS.md」这类行，不参与 LLM 协议。
     *
     * <p>dsh 对应：composer 组装 context 时产出的 "context-injection" part。
     */
    record ContextInjectedEvent(String name, String summary) implements AgentEvent {}

    /** 思考内容（reasoning_content / reasoning / thinking 三字段统一后） */
    record ThinkingEvent(String content) implements AgentEvent {}

    /** 正文增量（流式逐 token） */
    record TextDeltaEvent(String content) implements AgentEvent {}

    /** 模型发起一次工具调用（参数可能为流式碎片阶段的不完整 JSON，用 JsonNode 承接） */
    record ToolCallEvent(String id, String toolName, JsonNode input) implements AgentEvent {}

    /** 工具执行结果 */
    record ToolResultEvent(String id, String toolName, JsonNode output, boolean isError) implements AgentEvent {}

    /** 错误事件（用户取消、LLM 异常、工具异常等） */
    record ErrorEvent(String message) implements AgentEvent {}

    /** 正常结束标记 */
    record DoneEvent() implements AgentEvent {}

    /**
     * 工具执行前的「人工审批」请求：PermissionEngine 返回 Confirm 决策，且当前
     * 环境配置了 waitForUserConfirm 回调时，AgentLoop 不会中断，而是发出本事件
     * 等待用户在会话内批准/拒绝后继续。对应前端的一个「待确认卡片」。
     *
     * @param confirmId 本确认的全局唯一 id，前端批准/拒绝时必须原样带回（REST）。
     * @param toolName  需要确认的工具
     * @param message   面向用户的确认说明（理由 + 影响）
     * @param tags      命中的破坏性标签名称（前端按类型显示不同颜色/图标）
     * @param input     工具参数（展示给用户判断用）
     */
    record ConfirmationRequestEvent(String confirmId, String toolName, String message,
                                    java.util.List<String> tags, JsonNode input)
            implements AgentEvent {}

    /**
     * Agent 主动询问用户（ask_user 工具场景）：Agent 在流程中暂停并抛出一个问题，
     * 等待用户作答后把答案作为工具结果回给 LLM 继续。对应前端的一个「询问卡片」。
     *
     * @param askId    全局唯一 id，前端作答时必须原样带回（REST）。
     * @param question 面向用户的提问
     * @param hint     可选提示（候选答案 / 说明），可为 null
     * @param options  可选结构化短选项（2-4 个），前端渲染成按钮并自动补「其他」；可为 null
     */
    record AskUserEvent(String askId, String question, String hint, java.util.List<String> options) implements AgentEvent {}
}
