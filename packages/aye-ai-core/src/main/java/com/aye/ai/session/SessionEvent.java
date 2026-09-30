package com.aye.ai.session;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一条不可变会话事件（日志行）。
 *
 * <p>对应 dsh {@code SessionEvent<T>}：{@code type} 判别 + 单调 {@code seq}
 * （seq = append 时日志长度，全会话连续）+ 毫秒时间戳 + JSON payload。
 *
 * <p>与 dsh 一样，持久化行与事件 1:1 映射：{@code (sessionId, seq, type, tsMs,
 * turnNo, stepNo, ignorable, surfaceOp, sourceSeqs, data)}，没有并行的持久化 schema。
 *
 * @param type      事件类型，见 {@link SessionEventType}
 * @param seq       会话内单调连续序号
 * @param tsMs      Unix epoch 毫秒
 * @param turnNo    turn 坐标，不适用为 -1
 * @param stepNo    step 坐标，不适用为 -1
 * @param ignorable 为 true 时旧版本读取器可安全跳过；缺省（必需事件）必须拒绝重建
 * @param surfaceOp null=log-only；{@code "append"}=surface 尾部追加（投影为 LLM 消息）；
 *                  未来支持 {@code "replace:start-end"}（压缩影子化）
 * @param sourceSeqs 该事件引用的早期事件 seq（如 assistant/message 引用其 chunk 序列），可为 null
 * @param data       事件 payload（纯 JSON 对象）
 */
public record SessionEvent(
        String type,
        int seq,
        long tsMs,
        int turnNo,
        int stepNo,
        boolean ignorable,
        String surfaceOp,
        int[] sourceSeqs,
        ObjectNode data) {

    public static final int NO_COORD = -1;
    public static final String SURFACE_APPEND = "append";

    public boolean isSurface() {
        return SURFACE_APPEND.equals(surfaceOp) || (surfaceOp != null && surfaceOp.startsWith("replace:"));
    }
}
