package com.aye.ai.agent;

import com.aye.ai.event.AgentEvent;

/**
 * Agent 事件出口（对应 DeepWork 里 AsyncGenerator 的 {@code yield}）。
 *
 * <p>服务层实现此接口把事件桥接给 SSE（通常是 {@code BlockingQueue.put}）。
 * 纯 push 出口，不抛受检异常——TS 的 yield 本就没有受检异常；实现方自行吞掉
 * 中断/IO 异常（或包装为运行时异常），保证在流式消费 lambda 内可直接调用。
 */
@FunctionalInterface
public interface AgentEventSink {

    void accept(AgentEvent event);
}
