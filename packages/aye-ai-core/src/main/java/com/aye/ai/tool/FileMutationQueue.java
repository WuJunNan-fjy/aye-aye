package com.aye.ai.tool;

import java.util.function.Supplier;

/**
 * 文件修改串行化队列接口（对应 DeepWork 的 {@code FileMutationQueue}）。
 */
public interface FileMutationQueue {

    /**
     * 对某个绝对路径的写操作排队串行执行。
     * 同路径的上一个写操作完成（成功或失败）之后，下一个 fn 才会开始跑。
     */
    <T> T runSerialized(String absPath, Supplier<T> fn) throws Exception;
}
