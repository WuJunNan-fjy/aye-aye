package com.aye.server.session;

import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventLog;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 每会话 {@link SessionEventLog} 的注册表与生命周期管家（服务端事件溯源入口）。
 *
 * <p>职责：
 * <ul>
 *   <li><b>打开/恢复</b>：{@link #open(String)} 从 SQLite 按 seq 顺序 adopt 全部事件，
 *       未知必需类型 fail-closed，未知 ignorable 跳过；随后跑冷恢复闭合（孤儿 turn
 *       补 interrupted 终态）并立即落盘。整个恢复经 computeIfAbsent 原子完成，
 *       SSE 读线程与 agent 写线程并发打开同会话也只会 restore 一次。</li>
 *   <li><b>热路径持久化</b>：单守护线程每 80ms 批量事务 flush，活动 turn 不参与淘汰。</li>
 *   <li><b>实时监听</b>：SSE events 端点经 {@link #addLiveListener} 挂接，先拿游标
 *       再拉历史尾页，live/history 可能重叠，前端按 seq 去重。监听分发走每会话
 *       有界队列 + 单线程，绝不阻塞事件日志锁内的 agent append。</li>
 *   <li><b>LRU 淘汰</b>：非活动会话缓存上限 32 个，淘汰前强制 flush。</li>
 * </ul>
 */
public final class SessionEventManager {

    /**
     * 实时事件监听者。{@link #onDropped} 在推送队列积压超限、监听者被摘除时回调，
     * 调用方应据此主动关闭对端连接（SseEmitter.complete），让客户端走重连续传——
     * 否则连接上只剩 ping，客户端读停滞看门狗会被 ping 续命而永久无法自愈。
     */
    public interface LiveEventListener {
        void accept(SessionEvent event);

        default void onDropped(String reason) {
        }
    }

    private static final Logger LOG = Logger.getLogger(SessionEventManager.class.getName());
    private static final long FLUSH_INTERVAL_MS = 80L;
    private static final int MAX_IDLE_LOGS = 32;
    /** 单会话实时推送积压上限：超出视为监听端僵死，摘除该会话全部监听者。 */
    private static final int DISPATCH_QUEUE_CAP = 4096;

    private final SessionEventStore store;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, LiveSession> live = new ConcurrentHashMap<>();
    /** 有 agent 运行的会话：禁止淘汰。 */
    private final java.util.Set<String> active = ConcurrentHashMap.newKeySet();

    public SessionEventManager(SessionEventStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aye-event-flush");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::flushAll,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 打开（或复用）会话事件日志：恢复磁盘事件 → 冷恢复闭合 → 挂持久化订阅者。
     * 返回的 log 可直接作为 AgentLoop 的 eventLog。
     *
     * <p>computeIfAbsent 原子化：restore（含磁盘 IO）与 balance 只执行一次，
     * 消除「两个线程各自恢复、后写覆盖前写 / 双份合成事件主键冲突」竞态。
     * 工厂内不得回调本 map（ConcurrentHashMap 不允许同锁重入）。
     */
    public SessionEventLog open(String sessionId) {
        LiveSession ls = live.computeIfAbsent(sessionId, this::createLiveSession);
        ls.touch();
        return ls.log;
    }

    private LiveSession createLiveSession(String sessionId) {
        final SessionEventLog log = new SessionEventLog(sessionId);
        // restore：严格按 seq adopt；未知必需类型拒绝整个会话（fail-closed）。
        List<SessionEvent> rows = store.readAll(sessionId);
        int skipped = 0;
        for (SessionEvent e : rows) {
            if (!SessionEventLog.isKnownType(e.type())) {
                if (e.ignorable()) {
                    skipped++;
                    continue;
                }
                throw new IllegalStateException("会话事件含未知必需类型，无法恢复："
                        + sessionId + " seq=" + e.seq() + " type=" + e.type());
            }
            log.adopt(e);
        }
        if (skipped > 0) {
            LOG.info("恢复会话 " + sessionId + " 跳过 " + skipped + " 条未知 ignorable 事件");
        }

        EventPersistenceSubscriber persister =
                new EventPersistenceSubscriber(sessionId, store, log.lastSeq());
        LiveSession ls = new LiveSession(sessionId, log, persister);
        // 订阅顺序：先持久化（事实优先），再 live fan-out（异步入队，绝不做阻塞 IO）。
        log.subscribe(persister);
        log.subscribe(ev -> ls.dispatch(ev));

        // 冷恢复：孤儿 turn 补 interrupted 闭合；新增事件立刻同步落盘，不等调度 tick。
        // persister 直接 flush（不经 ls 提交任务），工厂线程内安全。
        if (log.balanceInterrupted()) {
            persister.flush();
            LOG.info("会话 " + sessionId + " 存在未闭合 turn，已按 interrupted 冷恢复闭合");
        }
        return ls;
    }

    /** 已在内存中打开的日志；未打开返回 null（不触发磁盘 IO）。 */
    public SessionEventLog getLive(String sessionId) {
        LiveSession ls = live.get(sessionId);
        return ls == null ? null : ls.log;
    }

    /** 标记会话进入活动态（agent 运行期间禁止淘汰）。 */
    public void markActive(String sessionId) {
        active.add(sessionId);
        LiveSession ls = live.get(sessionId);
        if (ls != null) ls.touch();
    }

    /** 活动态结束：先确保全部事实落盘，再允许 LRU 淘汰。 */
    public void markInactive(String sessionId) {
        active.remove(sessionId);
        LiveSession ls = live.get(sessionId);
        if (ls != null) {
            ls.touch();
            ls.persister.flush();
        }
        evictIdle();
    }

    /**
     * 挂接实时事件监听并返回订阅瞬间的 seq 游标。调用方随后应按 {@code after=游标}
     * 拉历史尾页补齐；两条通道在游标附近可能重叠，消费端按 seq 去重。
     */
    public int addLiveListener(String sessionId, LiveEventListener listener) {
        LiveSession ls = live.computeIfAbsent(sessionId, this::createLiveSession);
        ls.touch();
        ls.listeners.add(listener);
        return ls.log.seq();
    }

    public void removeLiveListener(String sessionId, LiveEventListener listener) {
        LiveSession ls = live.get(sessionId);
        if (ls != null) {
            ls.listeners.remove(listener);
            // 无监听者时释放分发线程，避免空闲会话长期占用线程。
            if (ls.listeners.isEmpty()) ls.shutdownDispatch();
        }
    }

    /** 等待会话至少 seq 之前（含）事件全部 durable；turn/end 后、SSE done 前调用。 */
    public boolean awaitDurable(String sessionId, int seq, long timeoutMs) {
        LiveSession ls = live.get(sessionId);
        if (ls == null) return store.maxSeq(sessionId) >= seq;
        return ls.persister.awaitDurable(seq, timeoutMs);
    }

    /**
     * 同步强制刷盘一次（turn 收尾兜底）：立即尝试把队列事件写入磁盘，
     * 不等 80ms 调度 tick。落盘失败由调用方按返回上下文处理（仅再抛运行时异常）。
     */
    public void flushNow(String sessionId) {
        LiveSession ls = live.get(sessionId);
        if (ls != null) ls.persister.flush();
    }

    /**
     * 尾页（seq &gt; after，升序）：优先读内存 live 日志（包含已提交但尚未刷盘的事件，
     * 消除「读磁盘与 live 推送之间」的竞态）；日志未打开时退化为磁盘读取。
     * limit &le; 0 表示不限。
     */
    public List<SessionEvent> readTailEvents(String sessionId, int afterSeq, int limit) {
        LiveSession ls = live.get(sessionId);
        if (ls == null) {
            return store.readTail(sessionId, afterSeq, limit <= 0 ? null : limit);
        }
        List<SessionEvent> all = ls.log.events(); // adopt 保证下标 == seq
        int start = Math.max(afterSeq + 1, 0);
        if (start >= all.size()) return List.of();
        int end = limit > 0 ? Math.min(all.size(), start + limit) : all.size();
        return new ArrayList<>(all.subList(start, end));
    }

    /** 头向分页（seq &lt; beforeSeq 的最近 limit 条，升序返回），供向上加载历史。 */
    public List<SessionEvent> readBeforeEvents(String sessionId, int beforeSeq, int limit) {
        int lim = limit <= 0 ? 100 : limit;
        LiveSession ls = live.get(sessionId);
        if (ls == null) return store.readBefore(sessionId, beforeSeq, lim);
        List<SessionEvent> all = ls.log.events();
        int end = Math.min(Math.max(beforeSeq, 0), all.size());
        int start = Math.max(0, end - lim);
        return new ArrayList<>(all.subList(start, end));
    }

    /** 当前日志长度（= 下一个事件 seq）；内存未打开时由磁盘 maxSeq 推算。 */
    public int currentSeq(String sessionId) {
        LiveSession ls = live.get(sessionId);
        return ls != null ? ls.log.seq() : store.maxSeq(sessionId) + 1;
    }

    /** 会话是否有运行中的 agent（运行中禁止删除）。 */
    public boolean isActive(String sessionId) {
        return active.contains(sessionId);
    }

    /** 批量查询会话当前 running turn（委托投影表单条聚合 SQL）。 */
    public java.util.Map<String, Integer> runningTurns(java.util.Collection<String> sessionIds) {
        return store.runningTurns(sessionIds);
    }

    /**
     * 删除会话事件：活动会话拒绝；否则逐出 live 缓存（强制最后 flush 无意义，直接关闭）
     * 并清空事件表/turn 投影。sessions/messages 行由 SessionStore 另行删除。
     */
    public void deleteSessionEvents(String sessionId) {
        if (active.contains(sessionId)) {
            throw new IllegalStateException("会话任务运行中，不能删除：" + sessionId);
        }
        LiveSession ls = live.remove(sessionId);
        if (ls != null) {
            ls.listeners.clear();
            ls.shutdownDispatch();
            ls.persister.close();
        }
        store.deleteSession(sessionId);
    }

    private void flushAll() {
        for (LiveSession ls : live.values()) {
            try {
                ls.persister.flush();
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "事件 flush 异常", ex);
            }
        }
    }

    /** LRU 淘汰非活动会话（按最久访问），活动会话始终保留。 */
    private void evictIdle() {
        int excess = live.size() - active.size() - MAX_IDLE_LOGS;
        if (excess <= 0) return;
        List<LiveSession> candidates = new ArrayList<>();
        for (var en : live.entrySet()) {
            if (!active.contains(en.getKey())) candidates.add(en.getValue());
        }
        candidates.sort(Comparator.comparingLong(l -> l.lastAccess.get()));
        int removed = 0;
        for (LiveSession ls : candidates) {
            if (removed >= excess) break;
            // 仍有 SSE 实时监听者的不淘汰，避免监听挂空。
            if (!ls.listeners.isEmpty()) continue;
            live.remove(ls.sessionId);
            ls.shutdownDispatch();
            ls.persister.close();
            removed++;
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (LiveSession ls : live.values()) {
            try {
                ls.persister.close();
                ls.shutdownDispatch();
            } catch (RuntimeException ignored) { }
        }
    }

    private static final class LiveSession {
        final String sessionId;
        final SessionEventLog log;
        final EventPersistenceSubscriber persister;
        final CopyOnWriteArrayList<LiveEventListener> listeners = new CopyOnWriteArrayList<>();
        private final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());
        /** 懒创建的单线程分发器：保证推送顺序且不占用 agent / 事件日志锁。 */
        private final AtomicReference<ExecutorService> dispatchExec = new AtomicReference<>();

        LiveSession(String sessionId, SessionEventLog log, EventPersistenceSubscriber persister) {
            this.sessionId = sessionId;
            this.log = log;
            this.persister = persister;
        }

        void touch() {
            lastAccess.set(System.currentTimeMillis());
        }

        /**
         * 异步入队分发：热路径（agent append 线程）只做一次有界队列 offer。
         * 监听端（SSE send）僵死导致队列堆满时，摘除全部监听者而非反压 agent——
         * 事实持久化不受影响，客户端可重连续传。
         */
        void dispatch(SessionEvent e) {
            if (listeners.isEmpty()) return;
            ExecutorService exec = ensureDispatchExec();
            try {
                exec.execute(() -> {
                    for (LiveEventListener l : listeners) {
                        try {
                            l.accept(e);
                        } catch (RuntimeException ex) {
                            LOG.log(Level.WARNING, "live 事件监听者异常，已 containment", ex);
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                String reason = "实时推送队列积压超过 " + DISPATCH_QUEUE_CAP
                        + "（监听端疑似僵死），摘除监听者并通知对端重连";
                LOG.log(Level.WARNING, "会话 " + sessionId + " " + reason
                        + "（事件持久化不受影响）");
                // 摘除并逐个通知：监听端据此 complete 连接，客户端重连续传，
                // 不能只 clear——否则该连接只剩 ping，前端看门狗被 ping 续命而永不重连。
                List<LiveEventListener> dropped = List.copyOf(listeners);
                listeners.clear();
                for (LiveEventListener l : dropped) {
                    try {
                        l.onDropped(reason);
                    } catch (RuntimeException ex) {
                        LOG.log(Level.WARNING, "监听者 onDropped 回调异常", ex);
                    }
                }
            }
        }

        private ExecutorService ensureDispatchExec() {
            ExecutorService existing = dispatchExec.get();
            if (existing != null) return existing;
            ExecutorService created = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(DISPATCH_QUEUE_CAP),
                    r -> {
                        Thread t = new Thread(r, "aye-event-dispatch-"
                                + sessionId.substring(0, Math.min(8, sessionId.length())));
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            return dispatchExec.compareAndSet(null, created) ? created : shutdownAndReturn(created);
        }

        private ExecutorService shutdownAndReturn(ExecutorService unused) {
            unused.shutdown();
            return dispatchExec.get();
        }

        void shutdownDispatch() {
            ExecutorService exec = dispatchExec.getAndSet(null);
            if (exec != null) exec.shutdown();
        }
    }
}
