package com.aye.server.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 本机长命令异步作业（run_local_async / local_job_poll 的后端支撑）。
 *
 * <p>模式照搬 SshJobManager：立即返回 job_id，进程在 daemon 线程上运行（不占工具线程池），
 * 输出有界（BoundedOutput），单任务 60 分钟上限，结束记录保留 30 分钟。
 * 作业仅在内存：服务重启后作业消失（部署账本仍在，阶段标 interrupted 后续跑）。
 */
public final class LocalJobManager {

    private static final Logger log = LoggerFactory.getLogger(LocalJobManager.class);

    static final int MAX_RUN_MIN = 60;
    static final int RETAIN_MIN = 30;
    private static final long MAX_RUN_MS = TimeUnit.MINUTES.toMillis(MAX_RUN_MIN);
    private static final long RETAIN_MS = TimeUnit.MINUTES.toMillis(RETAIN_MIN);
    /** local_job_poll 单次长轮询上限（工具超时 30s，留余量）。 */
    public static final long MAX_WAIT_MS = 25_000L;

    public enum Status { RUNNING, DONE, FAILED }

    private static final class Job {
        final String id;
        final String command;
        final Path workDir;
        final String tag;
        final long startedAt = System.currentTimeMillis();
        final BoundedOutput stdout = new BoundedOutput();
        final BoundedOutput stderr = new BoundedOutput();
        volatile Process process;
        volatile long finishedAt;
        volatile Status status = Status.RUNNING;
        volatile int exitCode = -1;
        volatile String error;

        Job(String id, String command, Path workDir, String tag) {
            this.id = id;
            this.command = command;
            this.workDir = workDir;
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
        public Status status() { return job.status; }
        public int exitCode() { return job.exitCode; }
        public String error() { return job.error; }
        public long elapsedMs() {
            return (job.finishedAt == 0 ? System.currentTimeMillis() : job.finishedAt) - job.startedAt;
        }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }
        public boolean outputTruncated() {
            return job.stdout.truncated() || job.stderr.truncated();
        }
    }

    private final LocalExecService exec;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aye-local-job");
        t.setDaemon(true);
        return t;
    });

    public LocalJobManager(LocalExecService exec) {
        this.exec = exec;
    }

    /** 启动异步命令；进程启动失败直接抛异常。 */
    public String start(String command, Path workDir, String tag) throws Exception {
        sweepExpired();
        Process process = exec.startProcess(command, workDir);
        Job job = new Job(UUID.randomUUID().toString(), command, workDir, tag);
        job.process = process;
        jobs.put(job.id, job);
        pool.submit(() -> run(job, process));
        log.info("[LocalJob] 启动 job={} cwd={} cmd={}", job.id, workDir,
                command.length() > 120 ? command.substring(0, 120) + "…" : command);
        return job.id;
    }

    /** 快照 + 长轮询（最多 25s）；作业不存在返回 null。 */
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

    /** Bean 销毁：销毁全部子进程树并停线程池。 */
    public void shutdown() {
        for (Job job : jobs.values()) {
            killProcessTree(job.process);
        }
        jobs.clear();
        pool.shutdownNow();
    }

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /**
     * 终止整个进程树（命令通常是 shell，真正干活的 npm/node/java 在孙进程）。
     * Windows 用 {@code taskkill /T /F}；POSIX 用 {@link ProcessHandle#descendants()}
     * 先温柔杀整棵树，3 秒后仍存活则强杀。
     */
    static void killProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        if (IS_WINDOWS) {
            try {
                Process tk = new ProcessBuilder(
                        "taskkill", "/PID", Long.toString(process.pid()), "/T", "/F")
                        .redirectErrorStream(true).start();
                tk.getInputStream().readAllBytes();
                tk.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("taskkill 失败，回退 JVM 强杀：{}", e.getMessage());
            }
            if (process.isAlive()) {
                killDescendants(process, true);
                process.destroyForcibly();
            }
            return;
        }
        killDescendants(process, false);
        process.destroy();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            killDescendants(process, true);
            process.destroyForcibly();
        }
    }

    /** 按后代快照终止子进程；force=true 强杀。叶子进程先杀，避免父进程重新拉起。 */
    private static void killDescendants(Process root, boolean force) {
        List<ProcessHandle> handles;
        try {
            handles = root.toHandle().descendants().toList();
        } catch (UnsupportedOperationException e) {
            return; // 该 JVM/OS 不支持枚举
        }
        List<ProcessHandle> reversed = new ArrayList<>(handles);
        Collections.reverse(reversed);
        for (ProcessHandle h : reversed) {
            if (!h.isAlive()) continue;
            if (force) h.destroyForcibly(); else h.destroy();
        }
        if (force) {
            for (ProcessHandle h : reversed) {
                try {
                    h.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 等待仅为尽量收敛，超时/中断均不影响后续强杀根进程
                }
            }
        }
    }

    private void run(Job job, Process process) {
        Thread outReader = pump(process.getInputStream(), job.stdout);
        Thread errReader = pump(process.getErrorStream(), job.stderr);
        boolean killedByLimit = false;
        try {
            long start = System.currentTimeMillis();
            while (process.isAlive()) {
                if (System.currentTimeMillis() - start > MAX_RUN_MS) {
                    killedByLimit = true;
                    break;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (killedByLimit) {
                killProcessTree(process);
            }
            try { outReader.join(3_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try { errReader.join(3_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        if (killedByLimit) {
            finish(job, Status.FAILED, -1,
                    "超过单任务 " + MAX_RUN_MIN + " 分钟上限，已被系统终止");
            return;
        }
        finish(job, Status.DONE, process.exitValue(), null);
    }

    private Thread pump(java.io.InputStream in, BoundedOutput buf) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[8192];
            try {
                int n;
                while ((n = in.read(b)) != -1) {
                    buf.append(b, 0, n);
                }
            } catch (java.io.IOException ignored) {
                // 进程结束时读取结束
            }
        }, "aye-local-job-io");
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
        log.info("[LocalJob] 结束 job={} status={} exit={} elapsed={}ms",
                job.id, status, exitCode, job.finishedAt - job.startedAt);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Job> e : jobs.entrySet()) {
            Job job = e.getValue();
            if (job.status != Status.RUNNING && job.finishedAt > 0
                    && now - job.finishedAt > RETAIN_MS) {
                jobs.remove(e.getKey(), job);
            }
        }
    }
}
