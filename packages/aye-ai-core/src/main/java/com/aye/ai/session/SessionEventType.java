package com.aye.ai.session;

/**
 * 会话事件类型常量（事件词汇表 v1）。
 *
 * <p>对齐 dsh SessionEventMap：事件是唯一事实，LLM 历史 / 人类 transcript /
 * 轨迹 / 审计全部是事件日志的下游投影。Aye 双栈（Java 发射 + TS 折叠），
 * 故类型用字符串常量而非 sealed 枚举，新增事件类型不需要修改 permits 列表；
 * 未知必需事件的拒绝策略在加载侧实现（见 {@link SessionEventLog}）。
 *
 * <p>坐标约定：除 {@link #TURN_START}/{@link #TURN_END}/{@link #SESSION_END_SEED}
 * 外，运行期事件均携带 turnNo；step 内事件携带 stepNo；不适用时为 -1。
 */
public final class SessionEventType {

    private SessionEventType() {}

    // ---- 边界 ----
    /** 一轮用户请求开始。data: {turn, modelId?, mode?} */
    public static final String TURN_START = "turn/start";
    /** 一轮结束。data: {turn, reason, modelId?, mode?, errorMessage?} */
    public static final String TURN_END = "turn/end";
    /** 一次模型往返开始。data: {turn, step} */
    public static final String STEP_START = "step/start";
    /** 一次模型往返结束。data: {turn, step} */
    public static final String STEP_END = "step/end";

    // ---- surface（投影为 LLM 消息） ----
    /** 用户消息（真人 / 注入警示 / 收尾指令 / 中途插话）。surface=append。 */
    public static final String USER_MESSAGE = "user/message";
    /** 组装完成的 assistant 消息（权威）。surface=append。 */
    public static final String ASSISTANT_MESSAGE = "assistant/message";
    /** 工具结果。surface=append（折叠到发起调用的 assistant 消息）。 */
    public static final String TOOL_RESULT = "tool/result";

    // ---- log-only 运行细节 ----
    /** 流式块（token 级回放保真；seq 连续契约要求 chunk 也持久化）。 */
    public static final String ASSISTANT_CHUNK = "assistant/chunk";
    /** 模型发起一次工具调用。 */
    public static final String TOOL_CALL = "tool/call";
    /** 权限决议（Aye 运维合规：自动放行 / 用户批准必须可区分）。 */
    public static final String TOOL_DECIDED = "tool/decided";
    /** 交互卡片请求（审批 / ask_user）。 */
    public static final String INTERACTION_REQUESTED = "interaction/requested";
    /** 交互卡片决议。 */
    public static final String INTERACTION_RESOLVED = "interaction/resolved";
    /** 请求头快照（system prompt + 工具目录 + 模型配置）——轨迹页的灵魂行。 */
    public static final String REQUEST_HEADER = "request/header";
    /** 上下文压缩摘要。 */
    public static final String COMPACTION_SUMMARY = "compaction/summary";
    /** 终端回显镜像（纯展示，ignorable，丢失不影响会话重建）。 */
    public static final String TERMINAL_MIRROR = "terminal/mirror";
    /** 上下文注入清单行（纯展示，ignorable）：内置工具集 / 系统提示。 */
    public static final String CONTEXT_INJECTED = "context/injected";
    /** 错误。 */
    public static final String ERROR = "error";
    /** resume / fork 种子边界（本生命周期首条实时写入之前）。 */
    public static final String SESSION_END_SEED = "session/end-seed";

    // ---- turn/end reason ----
    public static final String REASON_COMPLETED = "completed";
    public static final String REASON_ERROR = "error";
    public static final String REASON_ABORTED = "aborted";
    public static final String REASON_BLOCKED = "blocked";
    public static final String REASON_MAX_TOKENS = "max_tokens";
    /** 冷恢复合成：AgentLoop 永不主动发射该原因。 */
    public static final String REASON_INTERRUPTED = "interrupted";

    // ---- user/message source ----
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_INJECT = "inject";

    // ---- interaction kind ----
    public static final String INTERACTION_CONFIRM = "confirm";
    public static final String INTERACTION_ASK = "ask";
}
