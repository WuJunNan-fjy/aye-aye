package com.aye.server.controller;

import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventLog;
import com.aye.ai.util.Json;
import com.aye.server.session.SessionEventManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事件溯源读模型接口（dsh 同款 resume 协议）。
 *
 * <ul>
 *   <li>{@code GET /api/sessions/{id}/events?after=-1}：长连 SSE。连接即打开日志
 *       （冷会话自动 interrupted 闭合），先发 hello + snapshot（seq&gt;after 的历史），
 *       随后逐条推送 live 事件。snapshot 与 live 在挂接点附近可能重叠，消费端按 seq 去重。
 *       断线重连只需带上最后收到的 seq。</li>
 *   <li>{@code GET .../events/tail?after=&limit=} / {@code .../events/before?beforeSeq=&limit=}：
 *       JSON 分页，连续区间、不允许窗口空洞。</li>
 * </ul>
 *
 * <p>信封：{@code {type:'hello'|'snapshot'|'event'|'ping'|'error', ...}}，
 * 事件字段 camelCase：type/seq/tsMs/turnNo/stepNo/ignorable/surfaceOp/sourceSeqs/data。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
public class SessionEventController {

    private static final Logger LOG = Logger.getLogger(SessionEventController.class.getName());
    /** snapshot 单次补发上限；超出由前端用 before 分页向更老方向补齐。 */
    private static final int SNAPSHOT_CAP = 500;
    private static final long PING_INTERVAL_MS = 25_000L;

    /** 保活调度器（守护线程，全进程一条）：只负责定时调度，绝不在线程内同步 send。 */
    private static final ScheduledExecutorService KEEPALIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aye-events-sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    /**
     * ping 实际发送池：与调度线程隔离。某条半开连接的 send 阻塞时不能占住唯一的
     * 保活调度线程，否则其他所有健康会话的 ping 全部停发、被客户端集体误判半开重连。
     * 有界队列 + DiscardPolicy：极端饱和时丢弃个别 ping（客户端最多多等一个看门狗
     * 周期自愈重连），也不允许阻塞调度线程。
     */
    private static final ExecutorService PING_SENDERS = new ThreadPoolExecutor(
            0, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "aye-events-ping-send");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private final SessionEventManager manager;

    public SessionEventController(SessionEventManager manager) {
        this.manager = manager;
    }

    @GetMapping("/events")
    public SseEmitter events(@PathVariable String id,
                             @RequestParam(name = "after", defaultValue = "-1") int after) {
        SseEmitter emitter = new SseEmitter(0L);
        final SessionEventLog log;
        try {
            log = manager.open(id);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "打开会话事件日志失败：" + id, e);
            safeSend(emitter, Json.object().put("type", "error")
                    .put("message", "会话事件无法恢复：" + e.getMessage()));
            emitter.complete();
            return emitter;
        }

        AtomicBoolean closed = new AtomicBoolean(false);
        ScheduledFuture<?>[] pingHolder = new ScheduledFuture<?>[1];
        // 自引用 holder：匿名监听者内部（onDropped）需要摘除自身。
        AtomicReference<SessionEventManager.LiveEventListener> selfRef = new AtomicReference<>();
        SessionEventManager.LiveEventListener listener = new SessionEventManager.LiveEventListener() {
            @Override
            public void accept(SessionEvent ev) {
                if (closed.get()) return;
                try {
                    safeSend(emitter, Json.object().put("type", "event")
                            .set("event", Json.MAPPER.valueToTree(ev)));
                } catch (RuntimeException ex) {
                    // 推送失败（客户端已断）只静默停止推送；事件事实与 agent 运行不受影响。
                    closed.set(true);
                }
            }

            @Override
            public void onDropped(String reason) {
                // 推送积压被服务端摘除（对端疑似半开）。此处运行在 agent append 线程，
                // 不能同步 send（可能同样阻塞）；只 complete 让容器关闭连接，前端按
                // 「连接结束」自动以 after=lastSeq 重连，snapshot 补回积压期间事件。
                if (closed.compareAndSet(false, true)) {
                    LOG.info("会话 " + id + " SSE 监听者被摘除，主动 complete：" + reason);
                    // 主动摘除：complete 触发的 cleanup 会因 closed 已置位而跳过，
                    // 故这里显式摘除监听者（同时释放分发线程）。
                    SessionEventManager.LiveEventListener self = selfRef.get();
                    if (self != null) manager.removeLiveListener(id, self);
                    if (pingHolder[0] != null) pingHolder[0].cancel(false);
                    try {
                        emitter.complete();
                    } catch (RuntimeException ignored) {
                        // 容器侧已关闭即可
                    }
                }
            }
        };
        selfRef.set(listener);
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                manager.removeLiveListener(id, listener);
                if (pingHolder[0] != null) pingHolder[0].cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        // 先注册完成回调再挂监听：挂接后到回调注册之间的极端关闭窗口也能被兜底摘除。
        // 先挂监听再取快照：挂接窗口内的事件两路都会到，前端按 seq 去重，保证不丢。
        manager.addLiveListener(id, listener);

        try {
            safeSend(emitter, Json.object()
                    .put("type", "hello")
                    .put("sessionId", id)
                    .put("seq", log.seq()));
            List<SessionEvent> snapshot = manager.readTailEvents(id, after, SNAPSHOT_CAP);
            com.fasterxml.jackson.databind.node.ObjectNode snap = Json.object()
                    .put("type", "snapshot")
                    .put("hasMore", snapshot.size() >= SNAPSHOT_CAP);
            // Jackson 2.18 ObjectNode.set 是泛型方法，链式调用会退化成 JsonNode 失去 put，须单独成句。
            snap.set("events", Json.MAPPER.valueToTree(snapshot));
            safeSend(emitter, snap);
        } catch (RuntimeException e) {
            cleanup.run();
            emitter.complete();
            return emitter;
        }

        pingHolder[0] = KEEPALIVE.scheduleAtFixedRate(() -> {
            // 只入队、不在调度线程上同步 send：半开连接的阻塞写不得拖垮全局 ping。
            PING_SENDERS.execute(() -> {
                if (closed.get()) return;
                try {
                    safeSend(emitter, Json.object().put("type", "ping"));
                } catch (RuntimeException e) {
                    cleanup.run();
                    try { emitter.complete(); } catch (RuntimeException ignored) { }
                }
            });
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return emitter;
    }

    /** JSON 尾页：初始进房（最新一页）与断线 gap 修复使用。 */
    @GetMapping("/events/tail")
    public Map<String, Object> tail(@PathVariable String id,
                                    @RequestParam(name = "after", defaultValue = "-1") int after,
                                    @RequestParam(name = "limit", defaultValue = "200") int limit) {
        List<SessionEvent> events = manager.readTailEvents(id, after, limit);
        return Map.of(
                "events", events,
                "seq", manager.currentSeq(id),
                "hasMore", events.size() >= Math.max(limit, 1));
    }

    /** JSON 头向分页：向上加载更老历史（返回升序连续区间）。 */
    @GetMapping("/events/before")
    public Map<String, Object> before(@PathVariable String id,
                                      @RequestParam(name = "beforeSeq") int beforeSeq,
                                      @RequestParam(name = "limit", defaultValue = "200") int limit) {
        List<SessionEvent> events = manager.readBeforeEvents(id, beforeSeq, limit);
        int firstSeq = events.isEmpty() ? beforeSeq : events.get(0).seq();
        return Map.of(
                "events", events,
                "hasMore", events.size() >= Math.max(limit, 1) || firstSeq > 0);
    }

    private static void safeSend(SseEmitter emitter, com.fasterxml.jackson.databind.node.ObjectNode node) {
        try {
            emitter.send(SseEmitter.event().name("message").data(node, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
