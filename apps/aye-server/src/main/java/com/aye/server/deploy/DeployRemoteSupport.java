package com.aye.server.deploy;

import com.aye.server.ssh.SshJobManager;
import com.aye.server.ssh.SshToolSupport;

import java.util.regex.Pattern;

/**
 * 远程部署工具的公共校验与一次性执行。
 */
final class DeployRemoteSupport {

    static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    static final Pattern APP_DIR = Pattern.compile(
            "^(?:/opt|/root|~|/home/[a-z_][a-z0-9_-]*)?/aye-apps/[A-Za-z0-9_-]+/?$");

    /** shell 单引号转义。 */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String requireAppDir(String appDir) {
        String d = appDir == null ? "" : appDir.trim().replace('\\', '/');
        if (!APP_DIR.matcher(d).matches()) {
            throw new IllegalArgumentException(
                    "应用目录必须位于 aye-apps 约定目录内：" + appDir);
        }
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    static void requireName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("名称只允许字母数字、点、下划线、短横线：" + name);
        }
    }

    static int requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535：" + port);
        }
        return port;
    }

    /** 启动远程命令并等待至多 20s，返回快照。 */
    static SshJobManager.JobView runOneShot(SshToolSupport support, SshJobManager jobs,
                                             String hostKey, String command, String tag)
            throws Exception {
        String sessionId = support.resolveSession(hostKey);
        String jobId = jobs.start(sessionId, command, tag);
        SshJobManager.JobView view = jobs.snapshot(jobId, 20_000, 8_000);
        if (view == null) {
            throw new IllegalStateException("远程作业未返回结果：" + tag);
        }
        return view;
    }

    private DeployRemoteSupport() {}
}
