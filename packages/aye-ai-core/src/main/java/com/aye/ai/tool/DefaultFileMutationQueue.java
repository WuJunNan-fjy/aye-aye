package com.aye.ai.tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 文件修改串行化队列实现（对应 DeepWork 的 {@code DefaultFileMutationQueue}）。
 *
 * <p>每个绝对路径维护一把锁：同路径的写操作串行，不同路径之间完全并发。
 * 相比 DeepWork 的异步 Promise 链，这里用「按路径的 synchronized 锁」实现，
 * 语义一致（同路径上一个写完下一个才跑），且无需引入异步编排复杂度。
 */
public final class DefaultFileMutationQueue implements FileMutationQueue {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T runSerialized(String absPath, Supplier<T> fn) throws Exception {
        Object lock = locks.computeIfAbsent(absPath, k -> new Object());
        synchronized (lock) {
            return fn.get();
        }
    }
}
