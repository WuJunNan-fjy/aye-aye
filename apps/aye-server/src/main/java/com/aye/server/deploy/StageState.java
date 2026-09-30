package com.aye.server.deploy;

/**
 * 单个阶段的状态记录（序列化为 stages_json 的每个条目）。
 *
 * @param status  pending / running / done / failed / skipped
 * @param command 该阶段执行过的关键命令（可空）
 * @param purpose 命令用途（可空）
 * @param summary 面向用户的阶段小结（可空）
 * @param ts      更新时间戳
 */
public record StageState(
        String status,
        String command,
        String purpose,
        String summary,
        long ts) {

    public static StageState of(String status, String summary) {
        return new StageState(status, null, null, summary, System.currentTimeMillis());
    }
}
