package com.aye.server.ssh;

import com.jcraft.jsch.ChannelExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 长命令异步作业管理器（run_ssh_async / ssh_job_poll 的后端支撑）。
 *
 * <p>背景：同步 {@code run_ssh} 受工具 120s 超时限制，构建/打包/大日志导出等长命令
 * 会被掐断。异步模式下，{@code run_ssh_async} 打开独立 exec 通道后立即返回 job_id，
 * 通道在本管理器自己的 daemon 线程上继续运行（<b>不占用</b>工具执行线程池，也不受
 * 单次工具超时影响）；LLM 随后用 {@code ssh_job_poll} 长轮询状态与输出。
 *
 * <p>边界约定：
 * <ul>
 *   <li>单个任务最长运行 {@value #MAX_RUN_MIN} 分钟，到时断开通道并置 FAILED；</li>
 *   <li>输出有界：保留开头 {@value #HEAD_KB}KB + 结尾 {@value #TAIL_KB}KB（环形缓冲），
 *       轮询文本再按调用方上限截取尾部，防止超大日志撑爆内存/LLM 上下文；</li>
 *   <li>任务结束（DONE/FAILED）后记录保留 {@value #RETAIN_MIN} 分钟，懒清理；</li>
 *   <li>运行期间每 10s 续一次会话空闲计时，避免长命令跑到一半被 30 分钟空闲回收断开；</li>
 *   <li>作业状态仅在内存：服务重启 / SSH 会话断开都会结束任务（通道随会话关闭）。</li>
 * </ul>
 */
public final class SshJobManager {

    private static final Logger log = LoggerFactory.getLogger(SshJobManager.class);

    static final int HEAD_KB = 256;
    static final int TAIL_KB = 64;
    static final int MAX_RUN_MIN = 60;
    static final int RETAIN_MIN = 30;

    private static final int HEAD_CAP = HEAD_KB * 1024;
    private static final int TAIL_CAP = TAIL_KB * 1024;
    private static final long MAX_RUN_MS = TimeUnit.MINUTES.toMillis(MAX_RUN_MIN);
    private static final long RETAIN_MS = TimeUnit.MINUTES.toMillis(RETAIN_MIN);
    /** ssh_job_poll 单次长轮询上限（工具自身超时 30s，留余量）。 */
    public static final long MAX_WAIT_MS = 25_000L;

    public enum Status { RUNNING, DONE, FAILED }

    /** 有界输出：head 写满前全保留；写满后新增内容进定长环形 tail，落盘总量照常计数。 */
    private static final class BoundedOutput {
        private final ByteArrayOutputStream head = new ByteArrayOutputStream();
        private final byte[] tail = new byte[TAIL_CAP];
        private int tailPos;
        private boolean tailWrapped;
        private long total;

        synchronized void append(byte[] b, int off, int len) {
            total += len;
            int room = HEAD_CAP - head.size();
            if (room > 0) {
                int n = Math.min(room, len);
                head.write(b, off, n);
                off += n;
                len -= n;
            }
            for (int i = 0; i < len; i++) {
                tail[tailPos] = b[off + i];
                tailPos++;
                if (tailPos == tail.length) {
                    tailPos = 0;
                    tailWrapped = true;
                }
            }
        }

        synchronized boolean truncated() {
            return total > HEAD_CAP;
        }

        /** 完整渲染（head + 省略标记 + tail）。 */
        synchronized String renderAll() {
            String h = head.toString(StandardCharsets.UTF_8);
            if (total <= HEAD_CAP) return h;
            byte[] tailBytes;
            if (!tailWrapped) {
                tailBytes = new byte[tailPos];
                System.arraycopy(tail, 0, tailBytes, 0, tailPos);
            } else {
                tailBytes = new byte[tail.length];
                System.arraycopy(tail, tailPos, tailBytes, 0, tail.length - tailPos);
                System.arraycopy(tail, 0, tailBytes, tail.length - tailPos, tailPos);
            }
            long omitted = total - HEAD_CAP - tailBytes.length;
            return h + "\n…[输出过大，省略中间 " + Math.max(0, omitted)
                    + " 字节；以下为最后 " + TAIL_KB + "KB]…\n"
                    + new String(tailBytes, StandardCharsets.UTF_8);
        }

        /** 轮询用渲染：不超过 maxChars，超限时只保留最后 maxChars（盯尾部日志/构建结果）。 */
        synchronized String renderTail(int maxChars) {
            String all = renderAll();
            if (all.length() <= maxChars) return all;
            String tail2 = all.substring(all.length() - maxChars);
            return "…[仅显示最后 " + maxChars + " 字符，完整输出共 " + total + " 字节]…\n" + tail2;
        }
    }

    private static final class Job {
        final String id;
        final String sessionId;
        final long hostId;
        final String hostName;
        final String command;
        final String tag;
        final long startedAt = System.currentTimeMillis();
        final BoundedOutput stdout = new BoundedOutput();
        final BoundedOutput stderr = new BoundedOutput();
        volatile ChannelExec channel;
        volatile long finishedAt;
        volatile Status status = Status.RUNNING;
        volatile int exitCode = -1;
        volatile String error;

        Job(String id, String sessionId, long hostId, String hostName, String command, String tag) {
            this.id = id;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.hostName = hostName;
            this.command = command;
            this.tag = tag;
        }
    }

    /** 轮询快照（不可变视图）。 */
    public static final class JobView {
        private final Job job;
        private final String stdout;
        private final String stderr;

        private JobView(Job job, int maxChars) {
            this.job = job;
            this.stdout = job.stdout.renderTail(maxChars);
            this.stderr = job.stderr.renderTail(maxChars);
        }

        public String id() { return job.id; }
        public String command() { return job.command; }
        public String tag() { return job.tag; }
        public String hostName() { return job.hostName; }
        public Status status() { return job.status; }
        public int exitCode() { return job.exitCode; }
        public String error() { return job.error; }
        public long elapsedMs() {
            return (job.finishedAt == 0 ? System.currentTimeMillis() : job.finishedAt) - job.startedAt;
        }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }
        public boolean outputTruncated() { return job.stdout.truncated() || job.stderr.truncated(); }
    }

    private final SshSessionManager manager;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aye-ssh-job");
        t.setDaemon(true);
        return t;
    });

    public SshJobManager(SshSessionManager manager) {
        this.manager = manager;
    }

    /**
     * 启动异步命令：通道打开失败（会话断开等）直接抛异常，调用方收到工具错误；
     * 启动成功后命令生命周期与工具调用脱钩。
     */
    public String start(String sessionId, String command, String tag) {
        sweepExpired();
        ChannelExec channel = manager.openExec(sessionId, command);
        Map<String, Object> info = manager.getSessionInfo(sessionId);
        long hostId = info != null && info.get("hostId") instanceof Number n ? n.longValue() : -1L;
        String hostName = info != null && info.get("hostName") != null ? String.valueOf(info.get("hostName")) : null;

        Job job = new Job(UUID.randomUUID().toString(), sessionId, hostId, hostName, command, tag);
        job.channel = channel;
        jobs.put(job.id, job);
        pool.submit(() -> run(job, channel));
        log.info("[SshJob] 启动 job={} host={} cmd={}", job.id, hostName,
                command.length() > 120 ? command.substring(0, 120) + "…" : command);
        return job.id;
    }

    /**
     * 取作业快照；waitMs > 0 且作业运行中时长轮询（最多 {@value #MAX_WAIT_MS}/1000 秒），
     * 作业结束立即返回。作业不存在（错误 id / 结束超过保留期 / 服务重启）返回 null。
     */
    public JobView snapshot(String jobId, long waitMs, int outputMaxChars) throws InterruptedException {
        sweepExpired();
        Job job = jobs.get(jobId);
        if (job == null) return null;
        long wait = Math.max(0L, Math.min(waitMs, MAX_WAIT_MS));
        if (wait > 0 && job.status == Status.RUNNING) {
            long deadline = System.currentTimeMillis() + wait;
            synchronized (job) {
                while (job.status == Status.RUNNING) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) break;
                    job.wait(remain);
                }
            }
        }
        return new JobView(job, outputMaxChars);
    }

    /** Bean 销毁钩子：断开全部作业通道并停掉线程池（主机会话本身由 SshSessionManager 关闭）。 */
    public void shutdown() {
        for (Job job : jobs.values()) {
            ChannelExec ch = job.channel;
            if (ch != null) {
                try { ch.disconnect(); } catch (Exception ignored) {
                    // 关停时单个通道断开失败忽略
                }
            }
        }
        jobs.clear();
        pool.shutdownNow();
    }

    private void run(Job job, ChannelExec ch) {
        Thread outReader = null;
        Thread errReader = null;
        boolean killedByLimit = false;
        try {
            InputStream in = ch.getInputStream();
            InputStream ext = ch.getExtInputStream();
            outReader = pump(in, job.stdout);
            errReader = pump(ext, job.stderr);

            long start = System.currentTimeMillis();
            long lastTouchSec = -1;
            while (!ch.isClosed()) {
                long elapsedMs = System.currentTimeMillis() - start;
                if (elapsedMs > MAX_RUN_MS) {
                    killedByLimit = true;
                    break;
                }
                long elapsedSec = elapsedMs / 10_000;
                if (elapsedSec != lastTouchSec) {
                    // 续空闲计时：长命令期间不能让会话被 30 分钟空闲回收
                    manager.touch(job.sessionId);
                    lastTouchSec = elapsedSec;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            finish(job, Status.FAILED, -1, "作业监控异常：" + e.getMessage());
            return;
        } finally {
            if (killedByLimit) {
                try { ch.disconnect(); } catch (Exception ignored) {
                    // 超时断通道异常忽略，以超时状态为准
                }
            }
            if (outReader != null) {
                try { outReader.join(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (errReader != null) {
                try { errReader.join(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            try { ch.disconnect(); } catch (Exception ignored) {
                // 收尾断开忽略
            }
        }

        if (killedByLimit) {
            finish(job, Status.FAILED, -1, "超过单任务 " + MAX_RUN_MIN + " 分钟上限，已被系统终止");
            return;
        }
        int exit = ch.getExitStatus();
        if (exit >= 0) {
            finish(job, Status.DONE, exit, null);
        } else {
            finish(job, Status.FAILED, -1, "命令异常结束，未取得退出码（SSH 会话可能已断开）");
        }
    }

    private Thread pump(InputStream in, BoundedOutput buf) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) != -1) {
                    buf.append(b, 0, n);
                }
            } catch (IOException ignored) {
                // 通道关闭（正常结束/超时断开/会话断开）时读取结束
            }
        }, "aye-ssh-job-io");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void finish(Job job, Status status, int exitCode, String error) {
        synchronized (job) {
            job.status = status;
            job.exitCode = exitCode;
            job.error = error;
            job.finishedAt = System.currentTimeMillis();
            job.notifyAll();
        }
        log.info("[SshJob] 结束 job={} status={} exit={} elapsed={}ms",
                job.id, status, exitCode, job.finishedAt - job.startedAt);
    }

    /** 懒清理：结束超过保留期的作业（在 start/snapshot 时顺手扫一遍）。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Job> e : jobs.entrySet()) {
            Job job = e.getValue();
            if (job.status != Status.RUNNING && job.finishedAt > 0 && now - job.finishedAt > RETAIN_MS) {
                jobs.remove(e.getKey(), job);
            }
        }
    }
}
