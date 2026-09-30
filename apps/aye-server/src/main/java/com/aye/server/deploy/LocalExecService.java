package com.aye.server.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 本机命令执行：Windows 经 {@code cmd /c}，POSIX 经 {@code /bin/sh -c}。
 *
 * <p>cwd 由调用方指定（部署场景固定为工作区根）；环境变量继承当前用户会话，
 * 保证构建命令能找到 PATH 上的 JDK/Node。
 */
public final class LocalExecService {

    /** 同步执行结果。 */
    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /** 启动一个进程（异步作业用）。 */
    public Process startProcess(String command, Path workDir) throws IOException {
        if (workDir == null || !Files.isDirectory(workDir)) {
            throw new IOException("工作目录不存在或不是目录：" + workDir);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(workDir.toFile());
        return pb.start();
    }

    /** 同步执行秒级命令（版本检查等），超时杀进程并返回 timedOut=true。 */
    public ExecResult runSync(String command, Path workDir, int timeoutSec)
            throws IOException, InterruptedException {
        Process p = startProcess(command, workDir);
        StreamGobbler out = new StreamGobbler(p.getInputStream());
        StreamGobbler err = new StreamGobbler(p.getErrorStream());
        out.start();
        err.start();
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            // 与 LocalJobManager 同样按进程树终止，避免 shell 被杀而子进程逃逸
            LocalJobManager.killProcessTree(p);
        }
        out.join(2000);
        err.join(2000);
        return new ExecResult(finished ? p.exitValue() : -1,
                out.text(), err.text(), !finished);
    }

    /** 简单全量读取线程（同步执行用）。 */
    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private volatile String text = "";

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // 进程结束时读取结束
            }
        }

        String text() { return text; }
    }
}
