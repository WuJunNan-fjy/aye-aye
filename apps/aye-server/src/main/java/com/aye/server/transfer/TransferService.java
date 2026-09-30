package com.aye.server.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aye.server.ssh.SftpService;
import com.aye.server.ssh.SshSessionManager;
import com.aye.server.web.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片上传服务（方案 C）：分片落本地暂存 → complete 时 SequenceInputStream 直转 SFTP。
 */
@Service
public class TransferService {

    public static final int CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
    private static final long RESERVE_BYTES = 500L * 1024 * 1024;
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    /** 每会话同时进行的上传任务上限（防止 init 被刷出海量暂存任务）。 */
    private static final int MAX_ACTIVE_PER_SESSION = 16;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final SshSessionManager sessions;
    private final SftpService sftp;

    /** 每个 uploadId 一把锁（替代 intern，避免常量池问题）。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TransferService(@Value("${aye.data-dir}") String dataDir,
                           SshSessionManager sessions, SftpService sftp) {
        this.root = Path.of(dataDir, "transfers").toAbsolutePath().normalize();
        this.sessions = sessions;
        this.sftp = sftp;
    }

    /**
     * 启动清扫：
     * - 无 manifest 的脏目录：删除；
     * - 超过 24 小时的任务：删除；
     * - 上次崩溃在 COMPLETING：回退为 RECEIVING（分片保留可续）。
     *
     * <p>注意：重启后 SSH 会话内存表必然为空，不能据此判定任务失效，否则断点
     * 续传永远无法跨重启生效；运行期孤儿任务由 TTL 兜底清理。
     */
    @PostConstruct
    void startupCleanup() throws IOException {
        Files.createDirectories(root);
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) continue;
                TransferManifest m = readManifest(dir);
                boolean gone = m == null
                        || System.currentTimeMillis() - m.createdAt() > TTL_MILLIS;
                if (gone) {
                    deleteRecursively(dir);
                    continue;
                }
                if ("COMPLETING".equals(m.phase())) {
                    writeManifest(dir, withPhase(m, "RECEIVING"));
                }
            }
        }
    }

    public record InitRequest(String uploadId, String sessionId, String remoteDir,
                              String name, long size, String conflictStrategy) {}

    public record InitResponse(String uploadId, List<Integer> receivedChunks,
                               List<Integer> missingChunks, boolean resumed) {}

    // ── init：创建/恢复任务 ──

    public InitResponse init(InitRequest req) throws IOException {
        validateInit(req);
        Object lock = lockFor(req.uploadId());
        synchronized (lock) {
            Path dir = taskDir(req.uploadId());
            TransferManifest existing = readManifest(dir);
            int totalChunks = chunksOf(req.size());

            if (existing != null) {
                // 续传必须与原任务一致，防止同一 uploadId 被复用到其他目标
                if (!existing.sessionId().equals(req.sessionId())
                        || !existing.name().equals(req.name())
                        || existing.size() != req.size()
                        || !existing.remoteDir().equals(normalizeRemoteDir(req.remoteDir()))) {
                    throw new ApiException(409,
                            "续传请求与原上传任务不一致（目标/大小/会话），请取消后重新发起",
                            "RESUME_MISMATCH");
                }
                return resume(existing, dir);
            }

            if (countActiveTasks(req.sessionId()) >= MAX_ACTIVE_PER_SESSION) {
                throw new ApiException(429,
                        "进行中的上传任务过多（每会话上限 " + MAX_ACTIVE_PER_SESSION
                                + "），请等待完成或取消旧任务",
                        "TOO_MANY_TRANSFERS");
            }

            // 磁盘空间检查（含余量）
            FileStore store = Files.getFileStore(root);
            long usable = store.getUsableSpace();
            if (usable < req.size() + RESERVE_BYTES) {
                throw new ApiException(507,
                        "磁盘空间不足：需要约 " + (req.size() + RESERVE_BYTES)
                                + " 字节，当前可用 " + usable + " 字节",
                        "INSUFFICIENT_STORAGE");
            }

            Files.createDirectories(dir);
            TransferManifest m = new TransferManifest(
                    req.uploadId(), req.sessionId(), normalizeRemoteDir(req.remoteDir()),
                    req.name(), req.size(), totalChunks, CHUNK_SIZE,
                    req.conflictStrategy() == null || req.conflictStrategy().isBlank()
                            ? "error" : req.conflictStrategy(),
                    System.currentTimeMillis(), "RECEIVING");
            writeManifest(dir, m);
            return new InitResponse(req.uploadId(), List.of(), range(0, totalChunks), false);
        }
    }

    // ── chunk：接收单分片（流式落盘，不进内存） ──

    public void receiveChunk(String uploadId, int index, InputStream body, long contentLength)
            throws IOException {
        if (!TransferManifest.isSafeUploadId(uploadId)) {
            throw new ApiException(400, "非法 uploadId", "BAD_UPLOAD_ID");
        }
        Object lock = lockFor(uploadId);
        synchronized (lock) {
            Path dir = taskDir(uploadId);
            TransferManifest m = readManifest(dir);
            if (m == null) throw new ApiException(404, "任务不存在，请先 init", "NO_TRANSFER");
            if (index < 0 || index >= m.totalChunks()) {
                throw new ApiException(400, "分片序号越界：" + index, "BAD_CHUNK_INDEX");
            }
            long expected = expectedChunkSize(m, index);
            if (contentLength > 0 && contentLength != expected) {
                throw new ApiException(400, "Content-Length 与分片大小不符：期望 "
                        + expected + "，声明 " + contentLength, "CHUNK_LENGTH_MISMATCH");
            }
            Path tmp = dir.resolve("chunk_" + index + ".tmp");
            Path dst = dir.resolve("chunk_" + index);
            long written;
            try {
                written = copyBounded(body, tmp, expected);
            } catch (ApiException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            if (written != expected) {
                Files.deleteIfExists(tmp);
                throw new ApiException(400, "分片 " + index + " 大小不符：期望 "
                        + expected + "，实际 " + written, "CHUNK_SIZE_MISMATCH");
            }
            // 原子改名：只有完整落盘的分片才可见
            Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ── abort / status ──

    public void abort(String uploadId) throws IOException {
        if (!TransferManifest.isSafeUploadId(uploadId)) {
            throw new ApiException(400, "非法 uploadId", "BAD_UPLOAD_ID");
        }
        synchronized (lockFor(uploadId)) {
            Path dir = taskDir(uploadId);
            if (Files.exists(dir)) deleteRecursively(dir);
        }
        locks.remove(uploadId);
    }

    public Map<String, Object> status(String uploadId) throws IOException {
        if (!TransferManifest.isSafeUploadId(uploadId)) {
            throw new ApiException(400, "非法 uploadId", "BAD_UPLOAD_ID");
        }
        synchronized (lockFor(uploadId)) {
            Path dir = taskDir(uploadId);
            TransferManifest m = readManifest(dir);
            Map<String, Object> out = new LinkedHashMap<>();
            if (m == null) {
                out.put("exists", false);
                return out;
            }
            InitResponse r = resume(m, dir);
            out.put("exists", true);
            out.put("phase", m.phase());
            out.put("receivedChunks", r.receivedChunks());
            out.put("missingChunks", r.missingChunks());
            out.put("transferred", transferred(uploadId));
            return out;
        }
    }

    // ── complete：校验 → SequenceInputStream 直转 SFTP ──

    public void complete(String uploadId) throws Exception {
        if (!TransferManifest.isSafeUploadId(uploadId)) {
            throw new ApiException(400, "非法 uploadId", "BAD_UPLOAD_ID");
        }
        synchronized (lockFor(uploadId)) {
            Path dir = taskDir(uploadId);
            TransferManifest m = readManifest(dir);
            if (m == null) throw new ApiException(404, "任务不存在，请先 init", "NO_TRANSFER");
            if ("DONE".equals(m.phase())) return; // 幂等

            String remotePath = joinRemote(m.remoteDir(), m.name());

            // 冲突策略（在写之前处理）
            String strat = m.conflictStrategy() == null ? "error" : m.conflictStrategy();
            if ("error".equals(strat)) {
                if (sftp.stat(m.sessionId(), remotePath) != null) {
                    throw new ApiException(409, "远程文件已存在：" + remotePath
                            + "（如需覆盖请用 overwrite/backup 策略）", "REMOTE_EXISTS");
                }
            } else if ("backup".equals(strat)) {
                sftp.backupIfExists(m.sessionId(), remotePath);
            }

            // 标记 COMPLETING 并持久化：崩溃后重启清扫能识别（见 Task 5）
            writeManifest(dir, withPhase(m, "COMPLETING"));

            SequenceInputStream seq = null;
            boolean success = false;
            try {
                seq = openAssembledStream(dir, m);
                sftp.putStream(m.sessionId(), m.remoteDir(), m.name(), seq, new CountMonitor(uploadId));
                success = true;
            } finally {
                if (seq != null) seq.close();
                if (success) {
                    transferredNow.remove(uploadId);
                    deleteRecursively(dir);
                    locks.remove(uploadId);
                } else {
                    // 失败：分片保留，phase 回 RECEIVING，允许重试 complete
                    writeManifest(dir, withPhase(m, "RECEIVING"));
                }
            }
        }
    }

    private static TransferManifest withPhase(TransferManifest m, String phase) {
        return new TransferManifest(m.uploadId(), m.sessionId(), m.remoteDir(), m.name(),
                m.size(), m.totalChunks(), m.chunkSize(), m.conflictStrategy(),
                m.createdAt(), phase);
    }

    private static String joinRemote(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** SftpProgressMonitor：把已转发字节数写入内存表供 status 查询。 */
    private final class CountMonitor implements com.jcraft.jsch.SftpProgressMonitor {
        private final String uploadId;
        private long count;

        CountMonitor(String uploadId) { this.uploadId = uploadId; }

        @Override public void init(int op, String src, String dest, long max) { }

        @Override public boolean count(long bytes) {
            count += bytes;
            transferredNow.put(uploadId, count);
            return true;
        }

        @Override public void end() { }
    }

    // ===== 内部工具 =====

    /** 校验分片并构造顺序拼接流（可独立单测；complete 复用）。 */
    SequenceInputStream openAssembledStream(Path dir, TransferManifest m) throws IOException {
        List<Integer> got = presentChunks(dir, m.totalChunks());
        if (got.size() != m.totalChunks()) {
            List<Integer> miss = new ArrayList<>();
            for (int i = 0; i < m.totalChunks(); i++) if (!got.contains(i)) miss.add(i);
            throw new ApiException(409, "分片不完整，缺失序号：" + miss, "CHUNKS_INCOMPLETE");
        }
        // 逐片校验大小
        for (int i = 0; i < m.totalChunks(); i++) {
            long sz = Files.size(dir.resolve("chunk_" + i));
            if (sz != expectedChunkSize(m, i)) {
                throw new ApiException(400, "分片 " + i + " 大小损坏", "CHUNK_CORRUPT");
            }
        }
        List<InputStream> streams = new ArrayList<>();
        boolean ok = false;
        try {
            for (int i = 0; i < m.totalChunks(); i++) {
                streams.add(Files.newInputStream(dir.resolve("chunk_" + i)));
            }
            Enumeration<InputStream> en = Collections.enumeration(streams);
            SequenceInputStream seq = new SequenceInputStream(en);
            ok = true;
            return seq;
        } finally {
            if (!ok) for (InputStream s : streams) s.close();
        }
    }

    private InitResponse resume(TransferManifest m, Path dir) throws IOException {
        List<Integer> got = presentChunks(dir, m.totalChunks());
        List<Integer> miss = new ArrayList<>();
        for (int i = 0; i < m.totalChunks(); i++) if (!got.contains(i)) miss.add(i);
        return new InitResponse(m.uploadId(), got, miss, true);
    }

    private static List<Integer> presentChunks(Path dir, int total) {
        List<Integer> got = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (Files.isRegularFile(dir.resolve("chunk_" + i))) got.add(i);
        }
        return got;
    }

    static long expectedChunkSize(TransferManifest m, int index) {
        if (index < m.totalChunks() - 1) return m.chunkSize();
        return m.size() - (long) m.chunkSize() * (m.totalChunks() - 1);
    }

    static int chunksOf(long size) {
        return (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    private void validateInit(InitRequest req) {
        if (req == null) throw new ApiException(400, "请求体为空", "BAD_REQUEST");
        if (!TransferManifest.isSafeUploadId(req.uploadId())) {
            throw new ApiException(400, "非法 uploadId", "BAD_UPLOAD_ID");
        }
        if (!sessions.isSessionValid(req.sessionId())) {
            throw new ApiException(400, "SSH 会话不存在或已失效，请重新连接", "SESSION_INVALID");
        }
        if (req.remoteDir() == null || req.remoteDir().isBlank()) {
            throw new ApiException(400, "remoteDir 不能为空", "BAD_REMOTE_DIR");
        }
        if (!TransferManifest.isSafeFileName(req.name())) {
            throw new ApiException(400, "非法文件名", "BAD_FILE_NAME");
        }
        if (req.size() <= 0) throw new ApiException(400, "size 必须为正", "BAD_SIZE");
    }

    private static String normalizeRemoteDir(String d) {
        String s = d.replace('\\', '/').trim();
        if (s.length() > 1) s = s.replaceAll("/+$", "");
        return s;
    }

    private Path taskDir(String uploadId) {
        Path p = root.resolve(uploadId).normalize();
        if (!p.startsWith(root)) {
            throw new ApiException(400, "非法任务路径", "BAD_UPLOAD_ID");
        }
        return p;
    }

    private Object lockFor(String uploadId) {
        return locks.computeIfAbsent(uploadId, k -> new Object());
    }

    /** 统计某会话当前暂存中的任务数（init 频控用）。 */
    private long countActiveTasks(String sessionId) throws IOException {
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).filter(d -> {
                TransferManifest m = readManifest(d);
                return m != null && sessionId.equals(m.sessionId());
            }).count();
        }
    }

    /**
     * 限长落盘：最多写入 {@code limit} 字节；超出立即抛 413（即使 Content-Length 缺失，
     * 声明的 5MB 分片也无法灌入超大 body）。返回实际写入字节数。
     */
    private static long copyBounded(InputStream in, Path target, long limit) throws IOException {
        long written = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (written + n > limit) {
                    throw new ApiException(413,
                            "分片超过声明大小上限（" + limit + " 字节），已中止接收",
                            "CHUNK_TOO_LARGE");
                }
                out.write(buf, 0, n);
                written += n;
            }
        }
        return written;
    }

    static TransferManifest readManifest(Path dir) {
        try {
            Path f = dir.resolve("manifest.json");
            if (!Files.isRegularFile(f)) return null;
            return MAPPER.readValue(f.toFile(), TransferManifest.class);
        } catch (IOException e) {
            return null;
        }
    }

    static void writeManifest(Path dir, TransferManifest m) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(), m);
    }

    private static List<Integer> range(int from, int until) {
        List<Integer> l = new ArrayList<>();
        for (int i = from; i < until; i++) l.add(i);
        return l;
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var paths = Files.walk(p)) {
            paths.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
            });
        }
    }

    // ── complete 阶段进度（Task 4 写入） ──
    private final ConcurrentHashMap<String, Long> transferredNow = new ConcurrentHashMap<>();
    public long transferred(String uploadId) {
        return transferredNow.getOrDefault(uploadId, -1L);
    }
}
