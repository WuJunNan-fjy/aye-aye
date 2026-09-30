package com.aye.ai.agent;

import com.aye.ai.session.SessionEventLog;
import com.aye.ai.tool.ToolContext;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Agent Loop 配置选项（对应 DeepWork 的 {@code AgentLoopOptions}）。
 */
public final class AgentLoopOptions {

    private Integer maxSteps;
    private BooleanSupplier aborted;
    private Double temperature;
    private ToolContext toolContext;
    private Integer maxOutputTokens;
    private Integer contextWindowTokens;
    private Boolean summarizeEnabled;
    /**
     * 中途插话供应器：AgentLoop 在每个步边界（LLM 调用前）调用一次，drain 并返回
     * 缓冲的用户指示（多条已合并）；无插话时返回 null。协作式注入，不打断进行中的
     * LLM 流式 / 工具执行。
     */
    private Supplier<String> interjections;
    /**
     * 系统提示补充段（如长期记忆）：非空时作为「## 额外指令」追加进 system message。
     * 由编排层在启动 agentLoop 前一次性提供；运行中不热替换，下一轮对话才生效。
     */
    private String systemSupplement;
    /**
     * 环境信息快照（如本会话已配置主机的 host_key/连接状态）：非空时拼进 system message
     * 的「## 环境信息」段。由编排层在启动 agentLoop 前一次性提供；内容可能已过期，
     * 提示词允许模型在需要时调只读工具刷新。
     */
    private String envSupplement;
    /**
     * 事件溯源会话日志（dsh 架构唯一事实源）。非空时 AgentLoop 在双写旧 AgentEvent 的
     * 同时，把 turn/step、chunk、assistant/message、tool/call+result、interaction、
     * request/header、注入 user/message 等全部事实提交到该日志；为 null 时退化为旧行为。
     */
    private SessionEventLog eventLog;
    /**
     * 本次 agentLoop 调用所属的 turn 序号（由编排层在 turn/start 时确定）。
     * 仅在 {@link #eventLog} 非空时有意义；缺省 1。
     */
    private Integer turnNo;
    /** 会话模式标记（如 normal / thinking），写入 turn 与 request/header 事件，仅供展示。 */
    private String mode;

    public Integer maxSteps() { return maxSteps; }
    public AgentLoopOptions maxSteps(Integer v) { this.maxSteps = v; return this; }

    public BooleanSupplier aborted() { return aborted; }
    public AgentLoopOptions aborted(BooleanSupplier v) { this.aborted = v; return this; }

    public Double temperature() { return temperature; }
    public AgentLoopOptions temperature(Double v) { this.temperature = v; return this; }

    public ToolContext toolContext() { return toolContext; }
    public AgentLoopOptions toolContext(ToolContext v) { this.toolContext = v; return this; }

    public Integer maxOutputTokens() { return maxOutputTokens; }
    public AgentLoopOptions maxOutputTokens(Integer v) { this.maxOutputTokens = v; return this; }

    /** 上下文窗口（token），上下文智能总结用于估算容量；不设则用默认值。 */
    public Integer contextWindowTokens() { return contextWindowTokens; }
    public AgentLoopOptions contextWindowTokens(Integer v) { this.contextWindowTokens = v; return this; }

    /** 是否启用 LLM 语义总结（模式）。false 时只做抽取式压缩。默认 true。 */
    public Boolean summarizeEnabled() { return summarizeEnabled; }
    public AgentLoopOptions summarizeEnabled(Boolean v) { this.summarizeEnabled = v; return this; }

    /** 可选：中途插话供应器（步边界 drain，返回合并后的指示文本，无则 null）。 */
    public Supplier<String> interjections() { return interjections; }
    public AgentLoopOptions interjections(Supplier<String> v) { this.interjections = v; return this; }

    /** 可选：系统提示补充段（长期记忆等），非空时追加进 system message。 */
    public String systemSupplement() { return systemSupplement; }
    public AgentLoopOptions systemSupplement(String v) { this.systemSupplement = v; return this; }

    /** 可选：环境信息快照（主机快照等），非空时拼进「## 环境信息」段。 */
    public String envSupplement() { return envSupplement; }
    public AgentLoopOptions envSupplement(String v) { this.envSupplement = v; return this; }

    /** 可选：事件溯源会话日志；非空时 AgentLoop 双写全部运行事实。 */
    public SessionEventLog eventLog() { return eventLog; }
    public AgentLoopOptions eventLog(SessionEventLog v) { this.eventLog = v; return this; }

    /** eventLog 非空时的 turn 坐标（缺省 1）。 */
    public Integer turnNo() { return turnNo; }
    public AgentLoopOptions turnNo(Integer v) { this.turnNo = v; return this; }

    /** 可选：会话模式标记（写入事件，仅供展示）。 */
    public String mode() { return mode; }
    public AgentLoopOptions mode(String v) { this.mode = v; return this; }
}
