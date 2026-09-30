package com.aye.server.transfer;

/**
 * 分片上传任务清单。序列化为任务目录下的 manifest.json；
 * 后端重启后靠它恢复「已收分片」状态。
 *
 * @param uploadId         客户端确定性生成的任务 id（只允许 [A-Za-z0-9_-]）
 * @param sessionId        SSH 会话 id
 * @param remoteDir        远程目标目录
 * @param name             远程文件名
 * @param size             文件总字节
 * @param totalChunks      分片总数
 * @param chunkSize        常规分片大小（最后一片可小于此值）
 * @param conflictStrategy error(默认) / overwrite / backup
 * @param createdAt        创建时间（epoch millis）
 * @param phase            RECEIVING / COMPLETING / DONE
 */
public record TransferManifest(
        String uploadId,
        String sessionId,
        String remoteDir,
        String name,
        long size,
        int totalChunks,
        int chunkSize,
        String conflictStrategy,
        long createdAt,
        String phase) {

    /** uploadId 只允许安全字符：作为目录名使用，防路径穿越。 */
    public static boolean isSafeUploadId(String s) {
        return s != null && s.length() >= 8 && s.length() <= 128
                && s.matches("[A-Za-z0-9_-]+");
    }

    /** 远程文件名不允许带路径分隔符。 */
    public static boolean isSafeFileName(String s) {
        return s != null && !s.isBlank() && s.length() <= 255
                && !s.contains("/") && !s.contains("\\")
                && !s.contains("\0") && !".".equals(s) && !"..".equals(s);
    }
}
