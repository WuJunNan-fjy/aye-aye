package com.aye.ai.agent;

/** Agent Loop 全局常量（对应 DeepWork 的 {@code @deepwork/shared} 常量）。 */
public final class AgentConstants {

    private AgentConstants() {}

    public static final int DEFAULT_MAX_STEPS = 10;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8000;

    /** 死循环检测阈值：连续 N 次以「相同工具 + 相同参数」调用即判定为疑似卡死。 */
    public static final int DOOM_LOOP_THRESHOLD = 3;
}
