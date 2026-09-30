package com.aye.server.chat;

import com.aye.server.controller.AgentConfirmController;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * 挂起交互中心 —— Agent 主循环与用户之间的双向通道。
 *
 * <p>核心机制：AgentLoop 的事件出口是单向的，当 PermissionEngine 返回 Confirm 决策、
 * 或 Agent 主动调用 ask_user 工具时，AgentLoop 需要「暂停并等用户回传」。
 * 本组件作为桥：
 * <ol>
 *   <li>Agent 侧（worker 线程）通过 {@code awaitSessionConfirm} / {@code awaitSessionAsk}
 *       阻塞等待一个 {@link CompletableFuture}；</li>
 *   <li>交互卡片事件（interaction/requested）由 AgentLoop 的 wrapper 写入事件日志，
 *       v1 SSE 的 confirmation_request / ask_user 推送由 ChatService 闭包自行完成，
 *       本组件不再持有任何 SSE emitter；</li>
 *   <li>前端调用 {@link AgentConfirmController} 的 REST 端点回传批准/作答，
 *       本组件把结果 {@code complete} 到该 future；</li>
 *   <li>被阻塞的 Agent 线程 {@code await} 到结果后继续主循环。</li>
 * </ol>
 *
 * <p>结束语义：用户决议 → 正常完成；等待信号 aborted（v1 断连 / v2 显式取消）→
 * confirm 按拒绝、ask 按跳过（v1 既有行为不变）；超过 {@link #INTERACTION_TIMEOUT_MS}
 * 仍无决议 → 按拒绝/跳过闭合且标记 interrupted，释放 agent 线程（D4）。
 *
 * <p>并发安全：所有挂起项放在 ConcurrentHashMap，决议/超时/异常后均 remove。
 */
@Component
public class PendingInteractionHub {

    /** 等待响应期间的轮询间隔：阻塞式 get，CPU 开销极低；同时用于探测 aborted。 */
    private static final long POLL_INTERVAL_MS = 1000L;

    /** 交互等待总超时：10 分钟无决议自动闭合，避免 agent 线程被无限挂住（D4）。 */
    static final long INTERACTION_TIMEOUT_MS = 10L * 60L * 1000L;

    /** 挂起中的确认响应：confirmId → CompletableFuture&lt;ConfirmReply&gt;（approved + 是否记住授权） */
    private final ConcurrentHashMap<String, CompletableFuture<ConfirmReply>> pendingConfirms = new ConcurrentHashMap<>();

    /** 用户对一次确认的回答：approved=是否批准；remember=是否对本会话相同命令/参数免再询问。 */
    public record ConfirmReply(boolean approved, boolean remember) {}

    /** 挂起中的询问响应：askId → CompletableFuture&lt;String&gt;（作答文本，null/空=取消） */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingAsks = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // 确认（人工审批）
    // ---------------------------------------------------------------------------

    /**
     * 由 Agent 线程调用：注册一次确认请求并阻塞等待用户批准/拒绝。
     *
     * <p>调用方负责在此之前把交互卡片送到用户面前（v1 SSE / 事件日志 wrapper）。
     * aborted 置位时立即按拒绝返回（v1 断连即拒绝的既有语义）；
     * 超过 {@link #INTERACTION_TIMEOUT_MS} 无决议时返回 interrupted 结果。
     */
    public CompletionState awaitSessionConfirm(String confirmId, BooleanSupplier aborted) {
        CompletableFuture<ConfirmReply> future = new CompletableFuture<>();
        pendingConfirms.put(confirmId, future);
        long deadline = System.currentTimeMillis() + INTERACTION_TIMEOUT_MS;
        try {
            while (true) {
                if (aborted.getAsBoolean()) {
                    return new CompletionState(new ConfirmReply(false, false));
                }
                long waitMs = Math.min(POLL_INTERVAL_MS,
                        Math.max(1L, deadline - System.currentTimeMillis()));
                try {
                    ConfirmReply reply = future.get(waitMs, TimeUnit.MILLISECONDS);
                    if (reply != null) {
                        return new CompletionState(reply);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new CompletionState(new ConfirmReply(false, false));
                } catch (ExecutionException e) {
                    return new CompletionState(new ConfirmReply(false, false));
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() >= deadline) {
                        return CompletionState.INTERRUPTED_CONFIRM;
                    }
                    // 尚未回传，继续轮询 aborted
                }
            }
        } finally {
            pendingConfirms.remove(confirmId);
        }
    }

    /** 由 REST 端点调用：回填一次确认的批准/拒绝结果（remember=本会话相同操作不再询问）。 */
    public boolean resolveConfirm(String confirmId, boolean approved, boolean remember) {
        CompletableFuture<ConfirmReply> future = pendingConfirms.get(confirmId);
        if (future == null) return false;
        future.complete(new ConfirmReply(approved, remember));
        return true;
    }

    // ---------------------------------------------------------------------------
    // 询问（ask_user）
    // ---------------------------------------------------------------------------

    /**
     * 由 Agent 线程调用：注册一次主动询问并阻塞等待用户作答。
     * aborted 置位或超时时返回 null 作答（跳过）；超时结果额外带 interrupted 标记。
     */
    public CompletionState awaitSessionAsk(String askId, BooleanSupplier aborted) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingAsks.put(askId, future);
        long deadline = System.currentTimeMillis() + INTERACTION_TIMEOUT_MS;
        try {
            while (true) {
                if (aborted.getAsBoolean()) {
                    return new CompletionState(null);
                }
                long waitMs = Math.min(POLL_INTERVAL_MS,
                        Math.max(1L, deadline - System.currentTimeMillis()));
                try {
                    String answer = future.get(waitMs, TimeUnit.MILLISECONDS);
                    if (answer != null) {
                        return new CompletionState(answer);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new CompletionState(null);
                } catch (ExecutionException e) {
                    return new CompletionState(null);
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() >= deadline) {
                        return CompletionState.INTERRUPTED_ASK;
                    }
                    // 尚未作答，继续轮询 aborted
                }
            }
        } finally {
            pendingAsks.remove(askId);
        }
    }

    /** 由 REST 端点调用：回填一次主动询问的作答。 */
    public boolean resolveAsk(String askId, String answer) {
        CompletableFuture<String> future = pendingAsks.get(askId);
        if (future == null) return false;
        future.complete(answer);
        return true;
    }

    /** 一个交互的完成结果。interrupted=true 表示等待超时等非用户原因的闭合。 */
    public record CompletionState(Object value, boolean interrupted) {

        static final CompletionState INTERRUPTED_CONFIRM =
                new CompletionState(new ConfirmReply(false, false), true);
        static final CompletionState INTERRUPTED_ASK = new CompletionState(null, true);

        public CompletionState(Object value) {
            this(value, false);
        }

        public boolean approved() {
            return value instanceof ConfirmReply r && r.approved();
        }

        /** 用户是否勾选了「本会话相同操作不再询问」。 */
        public boolean remembered() {
            return value instanceof ConfirmReply r && r.remember();
        }

        public String answer() {
            return value instanceof String s ? s : null;
        }

        public boolean completed() {
            return value != null;
        }
    }
}
