package com.aye.server.session;

import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 每会话事件持久化订阅者：热路径只入队（非阻塞契约），由管理器的单线程调度器
 * 每 ~80ms 批量事务落盘（对齐 dsh 的 durable subscriber）。
 *
 * <p>落盘失败时整批退回队首等待下一轮重试，事件不丢；{@link #awaitDurable}
 * 供 turn/end 后、SSE done 前确保事实已入库。
 */
final class EventPersistenceSubscriber implements Consumer<SessionEvent> {

    private static final Logger LOG = Logger.getLogger(EventPersistenceSubscriber.class.getName());

    private final String sessionId;
    private final SessionEventStore store;
    private final ConcurrentLinkedDeque<SessionEvent> queue = new ConcurrentLinkedDeque<>();
    private final Object monitor = new Object();

    /** 已确认落盘的最大 seq（restore 完成后初始化为磁盘最大值）。 */
    private int durableSeq;
    private boolean closed;

    EventPersistenceSubscriber(String sessionId, SessionEventStore store, int initialDurableSeq) {
        this.sessionId = sessionId;
        this.store = store;
        this.durableSeq = initialDurableSeq;
    }

    @Override
    public void accept(SessionEvent event) {
        if (closed) return; // 已淘汰：迟到事件不再落盘（活动会话不会被淘汰，正常不可达）
        queue.addLast(event);
        // 终态事件立即同步落盘（而不是等 80ms tick）：本订阅者先于 live dispatch 订阅，
        // 保证 SSE 消费端一旦收到 turn/end，GET /sessions 的 runningTurn 投影必已闭合，
        // 前端结束边沿的列表刷新不会读到陈旧 running（侧栏运行点/保活集合即时收缩）。
        if (SessionEventType.TURN_END.equals(event.type())) {
            flush();
        }
    }

    /**
     * 把队列中事件批量落盘；无待写直接返回。
     * synchronized：与 agent 线程在 turn/end 时触发的同步 flush 互斥，避免同会话
     * 两个批次交错落盘（失败回批 + 主键冲突导致批次卡死）。
     */
    public synchronized void flush() {
        SessionEvent first = queue.pollFirst();
        if (first == null) return;
        List<SessionEvent> batch = new ArrayList<>();
        batch.add(first);
        SessionEvent e;
        while ((e = queue.pollFirst()) != null) batch.add(e);
        try {
            store.persistBatch(sessionId, batch);
            int last = batch.get(batch.size() - 1).seq();
            synchronized (monitor) {
                if (last > durableSeq) durableSeq = last;
                monitor.notifyAll();
            }
        } catch (RuntimeException ex) {
            // 整批失败：逆序退回队首，保持顺序，等下个 tick 重试（busy_timeout 已覆盖大多数 SQLite 锁竞争）。
            LOG.log(Level.WARNING, "session events 落盘失败，将重试：session=" + sessionId, ex);
            for (int i = batch.size() - 1; i >= 0; i--) queue.addFirst(batch.get(i));
        }
    }

    /**
     * 等待至少 seq 之前（含）的事件全部 durable。
     *
     * @return true=已落盘；false=超时
     */
    public boolean awaitDurable(int seq, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (monitor) {
            long remaining = timeoutMs;
            while (durableSeq < seq && !closed && remaining > 0) {
                try {
                    monitor.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                remaining = deadline - System.currentTimeMillis();
            }
            return durableSeq >= seq;
        }
    }

    /** 淘汰/关闭：拒绝新事件并做最后一次强制落盘。 */
    public void close() {
        closed = true;
        flush();
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }
}
