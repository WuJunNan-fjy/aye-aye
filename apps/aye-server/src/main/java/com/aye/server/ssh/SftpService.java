package com.aye.server.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.aye.server.web.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * SFTP 文件操作（翻译自旧 NestJS 的 {@code sftp.service.ts}）。
 *
 * <p>复用 {@link SshSessionManager#getSftp} 缓存的 SFTP 通道，避免每次操作都新建
 * channel 耗尽 SSH 的 MaxSessions。
 */
public final class SftpService {

    public record Download(byte[] data, String filename) {}

    private final SshSessionManager manager;

    public SftpService(SshSessionManager manager) {
        this.manager = manager;
    }

    /** 列出目录，返回 {@code [{name, path, type, size, modifiedTime}]} */
    public List<Map<String, Object>> list(String sessionId, String dirPath) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(dirPath);
            List<Map<String, Object>> files = new ArrayList<>();
            for (ChannelSftp.LsEntry e : entries) {
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                SftpATTRS attrs = e.getAttrs();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("path", joinPath(dirPath, name));
                m.put("type", attrs.isDir() ? "directory" : "file");
                m.put("size", attrs.getSize());
                m.put("modifiedTime", attrs.getMTime() * 1000L);
                files.add(m);
            }
            return files;
        }
    }

    public String readText(String sessionId, String filePath, int maxBytes) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            try (InputStream in = sftp.get(filePath)) {
                byte[] buf = new byte[8192];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int total = 0;
                int n;
                while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 读取文件尾部最多 maxBytes 字节（日志排障高频路径）。通过 SFTP 偏移量在服务端定位，
     * 不传输跳过的前缀；起始位置可能落在多字节字符中间，首字符偶发乱码属预期。
     */
    public String readTailText(String sessionId, String filePath, int maxBytes) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            long size = sftp.stat(filePath).getSize();
            long offset = Math.max(0L, size - maxBytes);
            try (InputStream in = sftp.get(filePath, null, offset)) {
                byte[] buf = new byte[8192];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int total = 0;
                int n;
                while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    public void writeText(String sessionId, String filePath, String content) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                sftp.put(in, filePath);
            }
        }
    }

    /**
     * 通过 SFTP 协议读取文件属性（一次 lstat 同时拿到「是否存在 + 大小 + 是否目录」），
     * 不依赖远程 shell 的 {@code stat} 命令（跨平台、无额外 exec 通道、无半开连接误判）。
     *
     * @return 文件属性；路径不存在时返回 null
     */
    public SftpATTRS stat(String sessionId, String filePath) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            try {
                ChannelSftp sftp = manager.getSftp(sessionId);
                return sftp.lstat(filePath);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
                throw new ApiException(500, "读取远程文件属性失败：" + e.getMessage(), "SFTP_STAT_FAILED");
            }
        }
    }

    public Download download(String sessionId, String filePath) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sftp.get(filePath, out);
            return new Download(out.toByteArray(), basename(filePath));
        }
    }

    /**
     * 流式下载：直接把远程文件写入调用方输出流（StreamingResponseBody 使用），
     * 内存占用与文件大小无关；调用期间持有 sftpLock。
     */
    public void writeRemoteTo(String sessionId, String filePath, OutputStream out) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            sftp.get(filePath, out);
        }
    }

    /**
     * 流式上传：调用方提供任意长度 InputStream，只开一次 SFTP put，
     * 内存占用与文件大小无关（GB 级部署包使用）。
     *
     * @param monitor 进度监视器（可为 null）
     */
    public void putStream(String sessionId, String dirPath, String filename,
                          InputStream in, SftpProgressMonitor monitor) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            ensureDirs(sftp, dirPath);
            sftp.put(in, joinPath(dirPath, filename), monitor, ChannelSftp.OVERWRITE);
        }
    }

    /** 简单远程备份：目标存在时 cp 一份到同目录 name.bak-<epochMillis>，返回备份路径；不存在返回 null。 */
    public String backupIfExists(String sessionId, String remoteFilePath) throws Exception {
        SftpATTRS a = stat(sessionId, remoteFilePath);
        if (a == null) return null;
        String bak = remoteFilePath + ".bak-" + System.currentTimeMillis();
        String escaped = remoteFilePath.replace("'", "'\\''");
        String bakEscaped = bak.replace("'", "'\\''");
        SshSessionManager.ExecResult r = manager.exec(sessionId,
                "cp -a '" + escaped + "' '" + bakEscaped + "'", 60);
        if (r.exitCode() != 0) {
            throw new ApiException(500, "远程备份失败：" + r.stderr(), "SFTP_BACKUP_FAILED");
        }
        return bak;
    }

    public void upload(String sessionId, String dirPath, byte[] buffer, String filename) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            ensureDirs(sftp, dirPath);
            try (InputStream in = new ByteArrayInputStream(buffer)) {
                sftp.put(in, joinPath(dirPath, filename));
            }
        }
    }

    public void remove(String sessionId, String filePath, boolean directory) throws Exception {
        if (directory) {
            // 目录用 rm -rf（SFTP rmdir 只能删空目录）；走 exec 通道，无需 SFTP 锁
            String escaped = filePath.replace("'", "'\\''");
            SshSessionManager.ExecResult result = manager.exec(sessionId, "rm -rf '" + escaped + "'", 30);
            if (result.exitCode() != 0) {
                throw new ApiException(500, "删除目录失败：" + result.stderr(), "SFTP_REMOVE_FAILED");
            }
        } else {
            synchronized (manager.sftpLock(sessionId)) {
                ChannelSftp sftp = manager.getSftp(sessionId);
                sftp.rm(filePath);
            }
        }
    }

    /** 重命名 / 移动（SFTP rename，同服务器内跨目录即移动） */
    public void rename(String sessionId, String oldPath, String newPath) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            sftp.rename(oldPath, newPath);
        }
    }

    /** 递归创建远程目录（等价 mkdir -p，逐级补齐缺失层级） */
    public void mkdir(String sessionId, String dirPath) throws Exception {
        synchronized (manager.sftpLock(sessionId)) {
            ChannelSftp sftp = manager.getSftp(sessionId);
            String normalized = dirPath.replace('\\', '/');
            StringBuilder cur = new StringBuilder();
            for (String seg : normalized.split("/")) {
                if (seg.isEmpty()) {
                    if (cur.length() == 0) cur.append("/");
                    continue;
                }
                if (!cur.toString().endsWith("/")) cur.append("/");
                cur.append(seg);
                ensureDir(sftp, cur.toString());
            }
        }
    }

    private static void ensureDir(ChannelSftp sftp, String dirPath) {
        try {
            sftp.mkdir(dirPath);
        } catch (SftpException e) {
            // 目录已存在不算错误；其它错误向上抛
            if (e.id != 4 && !String.valueOf(e.getMessage()).toLowerCase().contains("exist")) {
                throw new ApiException(500, "创建目录失败：" + e.getMessage(), "SFTP_ERROR");
            }
        }
    }

    /** 逐级补齐目录（mkdir -p 语义），供上传路径自动创建。 */
    private static void ensureDirs(ChannelSftp sftp, String dirPath) {
        String normalized = dirPath.replace('\\', '/');
        StringBuilder cur = new StringBuilder();
        for (String seg : normalized.split("/")) {
            if (seg.isEmpty()) {
                if (cur.length() == 0) cur.append('/');
                continue;
            }
            if (!cur.toString().endsWith("/")) cur.append('/');
            cur.append(seg);
            ensureDir(sftp, cur.toString());
        }
    }

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
