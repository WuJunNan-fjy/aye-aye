package com.aye.server.chat;

/**
 * 用户拖入对话的附件引用。
 *
 * @param name        原始文件名（展示/注入标注用）
 * @param path        附件绝对路径：桌面端为用户本机路径；浏览器端为 uploads 落盘路径
 * @param isDirectory true=目录引用（授权按目录前缀）；默认 false
 * @param pathToken   桌面端主进程对 path 颁发的 HMAC 来源凭证（启用 token 时必填；uploads 路径可空）
 */
public record ChatAttachment(String name, String path, boolean isDirectory, String pathToken) {

    /** 兼容旧调用方：无来源凭证的附件记录（浏览器 uploads 路径场景）。 */
    public ChatAttachment(String name, String path, boolean isDirectory) {
        this(name, path, isDirectory, null);
    }
}
