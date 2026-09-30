package com.aye.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.audit.AuditLedger;
import com.aye.ai.permission.DestructiveTag;
import com.aye.ai.permission.TrustLevel;
import com.aye.ai.session.RunCoordinates;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 工具执行上下文（对应 DeepWork 的 {@code ToolContext}）。
 *
 * <p>所有字段可选，保证工具可独立运行；骨架初始化时注入 audit / fileMutationQueue。
 */
public final class ToolContext {

    /** 中止信号（用户取消时触发） */
    private BooleanSupplier aborted;
    /** 会话 ID（旧名，兼容；新代码优先使用 sessionId） */
    private String conversationId;
    /** 会话 ID（骨架标准字段） */
    private String sessionId;
    /** 工作区绝对路径（工具层 resolvePath 的起点） */
    private String cwd;
    /** 用户信任级别（影响规则引擎的默认决策） */
    private TrustLevel trustLevel;
    /** 执行模式：readonly / write_confirm / all_confirm（前端传入，影响规则引擎） */
    private String executionMode;
    /** 审计账本 */
    private AuditLedger audit;
    /** 文件修改串行化队列 */
    private FileMutationQueue fileMutationQueue;
    /** 权限引擎要求用户确认时的等待回调 */
    private UserConfirmCallback waitForUserConfirm;
    /** Agent ask_user 工具主动询问用户时的回调 */
    private AskUserCallback askUser;
    /** 当前运行坐标 (turn, step)，由 AgentLoop 更新；交互回调据此定位事件归属 */
    private RunCoordinates runCoordinates;
    /** 权限引擎自动决议（allow/deny，不含人工确认）监听，由 AgentLoop 注入以落 tool/decided 事件 */
    private AutoDecisionListener autoDecisionListener;
    /** 自定义元数据 */
    private Map<String, Object> metadata;

    public boolean isAborted() {
        return aborted != null && aborted.getAsBoolean();
    }

    public BooleanSupplier aborted() { return aborted; }
    public ToolContext aborted(BooleanSupplier v) { this.aborted = v; return this; }

    public String conversationId() { return conversationId; }
    public ToolContext conversationId(String v) { this.conversationId = v; return this; }

    public String sessionId() { return sessionId; }
    public ToolContext sessionId(String v) { this.sessionId = v; return this; }

    public String cwd() { return cwd; }
    public ToolContext cwd(String v) { this.cwd = v; return this; }

    public TrustLevel trustLevel() { return trustLevel; }
    public ToolContext trustLevel(TrustLevel v) { this.trustLevel = v; return this; }

    public String executionMode() { return executionMode; }
    public ToolContext executionMode(String v) { this.executionMode = v; return this; }

    public AuditLedger audit() { return audit; }
    public ToolContext audit(AuditLedger v) { this.audit = v; return this; }

    public FileMutationQueue fileMutationQueue() { return fileMutationQueue; }
    public ToolContext fileMutationQueue(FileMutationQueue v) { this.fileMutationQueue = v; return this; }

    public UserConfirmCallback waitForUserConfirm() { return waitForUserConfirm; }
    public ToolContext waitForUserConfirm(UserConfirmCallback v) { this.waitForUserConfirm = v; return this; }

    public AskUserCallback askUser() { return askUser; }
    public ToolContext askUser(AskUserCallback v) { this.askUser = v; return this; }

    /** 当前运行坐标 (turn, step)；未接入事件流时为 null。 */
    public RunCoordinates runCoordinates() { return runCoordinates; }
    public ToolContext runCoordinates(RunCoordinates v) { this.runCoordinates = v; return this; }

    /** 自动决议监听；未注入时权限引擎只走审计、不落事件。 */
    public AutoDecisionListener autoDecisionListener() { return autoDecisionListener; }
    public ToolContext autoDecisionListener(AutoDecisionListener v) { this.autoDecisionListener = v; return this; }

    public Map<String, Object> metadata() { return metadata; }
    public ToolContext metadata(Map<String, Object> v) { this.metadata = v; return this; }

    /** 有效会话 ID（sessionId 优先，缺省 conversationId，再缺省 unknown） */
    public String effectiveSessionId() {
        if (sessionId != null && !sessionId.isEmpty()) return sessionId;
        if (conversationId != null && !conversationId.isEmpty()) return conversationId;
        return "unknown";
    }

    /** 有效工作目录（cwd 优先，缺省 user.dir） */
    public String effectiveCwd() {
        if (cwd != null && !cwd.isEmpty()) return cwd;
        return System.getProperty("user.dir");
    }

    /** 权限引擎要求用户确认时的等待回调（对应 DeepWork 的 {@code waitForUserConfirm}） */
    @FunctionalInterface
    public interface UserConfirmCallback {
        /**
         * 发出一次人工审批请求并阻塞等待用户批准/拒绝。
         *
         * @param callId    发起本次调用的 LLM 工具调用 id（事件留痕 tool/decided 关联用）
         * @param confirmId 确认 id（发送事件 + 回收响应都要用到，跨线程用 String 解耦）
         * @param toolName  需要确认的工具名
         * @param message   面向用户的确认说明
         * @param tags      命中的破坏性标签
         * @param input     工具参数（展示给用户判断用，可为 null）
         * @return true=批准（可继续执行），false=拒绝（按错误返回给 LLM）
         */
        boolean awaitConfirm(String callId, String confirmId, String toolName, String message,
                             List<DestructiveTag> tags, JsonNode input) throws Exception;
    }

    /** Agent ask_user 工具主动询问用户时的回调 */
    @FunctionalInterface
    public interface AskUserCallback {
        /**
         * 发出一次主动询问并阻塞等待用户作答。
         *
         * @param askId    询问 id（发送事件 + 回收响应都要用到）
         * @param question 面向用户的提问
         * @param hint     可选提示（候选答案 / 说明），可为 null
         * @param options  可选结构化短选项（2-4 个），前端渲染成按钮并自动补「其他」；为 null 时前端按自由作答渲染
         * @return 用户作答文本（返回 null 或空串视为取消/跳过）
         */
        String askUser(String askId, String question, String hint, List<String> options) throws Exception;
    }

    /** 审批等待结果：除批准/拒绝/记住外，还可表达「等待被超时等非用户原因中断」。 */
    public record UserConfirmResult(boolean approved, boolean remember, boolean interrupted) {
        public static UserConfirmResult decided(boolean approved, boolean remember) {
            return new UserConfirmResult(approved, remember, false);
        }

        /** 等待中断（超时）：按拒绝闭合，但事件需标记 interrupted。 */
        public static UserConfirmResult timedOut() {
            return new UserConfirmResult(false, false, true);
        }
    }

    /**
     * 富审批回调（可选）：实现方能区分「用户拒绝」与「等待超时中断」。
     * AgentLoop 的 interaction wrapper 识别本接口并据此落 interaction/resolved(interrupted)；
     * 普通 {@link UserConfirmCallback} 实现经 default 方法桥接，行为不变。
     */
    @FunctionalInterface
    public interface UserConfirmCallbackEx extends UserConfirmCallback {
        UserConfirmResult awaitConfirmEx(String callId, String confirmId, String toolName,
                                         String message, List<DestructiveTag> tags,
                                         JsonNode input) throws Exception;

        @Override
        default boolean awaitConfirm(String callId, String confirmId, String toolName,
                                     String message, List<DestructiveTag> tags,
                                     JsonNode input) throws Exception {
            return awaitConfirmEx(callId, confirmId, toolName, message, tags, input).approved();
        }
    }

    /** 询问等待结果：answer 为作答文本；interrupted=true 表示等待超时（按跳过闭合）。 */
    public record AskUserResult(String answer, boolean interrupted) {
        public static AskUserResult answered(String answer) {
            return new AskUserResult(answer, false);
        }

        public static AskUserResult timedOut() {
            return new AskUserResult(null, true);
        }
    }

    /** 富询问回调（可选）：语义同 {@link UserConfirmCallbackEx}。 */
    @FunctionalInterface
    public interface AskUserCallbackEx extends AskUserCallback {
        AskUserResult askUserEx(String askId, String question, String hint, List<String> options)
                throws Exception;

        @Override
        default String askUser(String askId, String question, String hint, List<String> options)
                throws Exception {
            return askUserEx(askId, question, hint, options).answer();
        }
    }

    /**
     * 权限引擎「自动决议」监听（会话授权 / 信任级 / 模式规则产生的 allow/deny）。
     * 人工点按产生的决议不经过这里（由交互回调路径自行落 decidedBy=user 事件）。
     * 实现必须不抛异常、不阻塞工具执行。
     */
    @FunctionalInterface
    public interface AutoDecisionListener {
        /**
         * @param decision allow / deny
         * @param mode     当时执行模式（readonly / write_confirm / all_confirm），可为 null
         * @param rule     命中规则说明；deny 时通常为拒绝原因，可为 null
         */
        void onAutoDecision(String callId, String decision, String mode, String rule);
    }
}
