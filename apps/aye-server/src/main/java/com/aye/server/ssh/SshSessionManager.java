package com.aye.server.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.aye.server.storage.HostStore;
import com.aye.server.storage.KnownHostsStore;
import com.aye.server.ssh.TofuHostKeyRepository.CheckOutcome;
import com.aye.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话级 SSH 连接管理器（翻译自旧 NestJS 的 {@code ssh.service.ts}）。
 *
 * <p>维护长效连接：终端 / SFTP / exec 都复用同一会话。会话用 {@code sessionId}
 * 索引，断线后前端重新 {@code connect} 即可。AI 的 run_ssh / sftp_* 工具通过
 * {@link #findByHostId(long)} 复用活跃会话。
 *
 * <p><b>安全加固（P0-4）</b>：
 * <ul>
 *   <li>主机密钥 TOFU 校验：{@link KnownHostsStore} + 自研 {@link TofuHostKeyRepository}，
 *       替换 {@code StrictHostKeyChecking=no}，抵御中间人；首连返回 {@code need_trust} 待前端确认。</li>
 *   <li>会话 ID：{@link SecureRandom} ≥128bit，不可预测。</li>
 *   <li>凭据非驻留：{@code SshSession} 不再保存密码/私钥/口令明文，连接建立后即
 *       {@code removeAllIdentity()} 擦除 JSch 内存中的私钥。</li>
 *   <li>空闲回收：记录 {@code lastActive}，30 分钟无活动自动断开（见 {@link #reapIdle}）。</li>
 * </ul>
 */
public final class SshSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SshSessionManager.class);

    /** 空闲 30 分钟回收 */
    private static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    /** SSH keepalive 间隔：每 30s 发一次全局请求，及时发现被 NAT/防火墙静默回收的半开连接 */
    private static final int SERVER_ALIVE_INTERVAL_MS = 30_000;
    /** 连续 3 次 keepalive 无响应（约 90s）即判定连接已死 */
    private static final int SERVER_ALIVE_COUNT_MAX = 3;

    /** 一次命令执行结果 */
    public record ExecResult(int exitCode, String stdout, String stderr, long durationMs) {}

    /** 连接结果：connected 携带 sessionId；need_trust 携带待确认的主机指纹 */
    public record ConnectResult(
            String status, String sessionId,
            String trustReason, String fingerprint, String address, int port) {

        static ConnectResult ok(String sessionId) {
            return new ConnectResult("connected", sessionId, null, null, null, 0);
        }

        static ConnectResult needTrust(CheckOutcome outcome, String fingerprint, String address, int port) {
            return new ConnectResult("need_trust", null,
                    outcome == CheckOutcome.CHANGED ? "CHANGED" : "NEW", fingerprint, address, port);
        }
    }

    /** 活跃会话：只保留连接本身与归属，不驻留任何凭据 */
    private static final class SshSession {
        final Session session;
        final long hostId;
        final String hostName;
        volatile ChannelSftp sftp;
        volatile boolean disconnected;
        volatile long lastActive = System.currentTimeMillis();
        /**
         * SFTP 通道串行锁：JSch 的单个 {@link ChannelSftp} 不是线程安全的（共用一条
         * SSH 通道流，并发 ls/get/put 会搅乱协议）。工具并行执行 / 子代理并行扇出时，
         * 同一会话的所有 SFTP 通道操作必须经此锁串行；exec 通道每次新开，不受此限。
         */
        final Object sftpLock = new Object();

        SshSession(Session session, long hostId, String hostName) {
            this.session = session;
            this.hostId = hostId;
            this.hostName = hostName;
        }
    }

    private final Map<String, SshSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService execPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aye-ssh-exec");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aye-ssh-reaper");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom secureRandom = new SecureRandom();
    private final HostStore hostStore;
    private final KnownHostsStore knownHostsStore;

    public SshSessionManager(HostStore hostStore, KnownHostsStore knownHostsStore) {
        this.hostStore = hostStore;
        this.knownHostsStore = knownHostsStore;
        reaper.scheduleWithFixedDelay(this::reapIdle, 60, 60, TimeUnit.SECONDS);
    }

    /** 通过 hostId 建立 SSH 连接；首连 / 指纹变化返回 need_trust（不经确认不建立连接） */
    public ConnectResult connect(long hostId, Map<String, Object> credentials) throws ApiException {
        Map<String, Object> host = hostStore.findOne(hostId);
        if (host == null) {
            throw new ApiException(404, "主机不存在：" + hostId, "HOST_NOT_FOUND");
        }

        String address = String.valueOf(host.get("address"));
        int port = ((Number) host.getOrDefault("port", 22)).intValue();
        String username = String.valueOf(host.get("username"));
        String hostName = String.valueOf(host.get("name"));
        String password = strOrNull(credentials.get("password"));
        String privateKey = strOrNull(credentials.get("privateKey"));
        String passphrase = strOrNull(credentials.get("passphrase"));

        JSch jsch = new JSch();
        TofuHostKeyRepository repo = new TofuHostKeyRepository(knownHostsStore);
        repo.bind(address, port);
        try {
            jsch.setHostKeyRepository(repo);
            jsch.setConfig("StrictHostKeyChecking", "yes");
            jsch.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");

            if (privateKey != null && !privateKey.isBlank()) {
                byte[] pass = (passphrase == null || passphrase.isBlank())
                        ? null : passphrase.getBytes(StandardCharsets.UTF_8);
                jsch.addIdentity("aye-tmp-" + System.nanoTime(),
                        privateKey.getBytes(StandardCharsets.UTF_8), null, pass);
            }

            Session session = jsch.getSession(username, address, port);
            if (password != null && !password.isBlank()) {
                session.setPassword(password);
            }
            // 保活：防止长连接闲置后被 NAT/防火墙静默回收形成「假活」半开连接
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
            session.connect(20_000);

            String sessionId = hostId + "-" + secureSuffix();
            sessions.put(sessionId, new SshSession(session, hostId, hostName));
            return ConnectResult.ok(sessionId);
        } catch (JSchException e) {
            // 主机密钥校验阶段抛出的异常（NEW/CHANGED）：不建立连接，交前端确认
            if (repo.lastOutcome() != CheckOutcome.OK) {
                return ConnectResult.needTrust(repo.lastOutcome(), repo.lastFingerprint(), address, port);
            }
            throw translateConnectError(e);
        } finally {
            // 连接已建立：私钥不再需要驻留在 JSch 内存，立即擦除以减少敏感数据生命期
            try {
                jsch.removeAllIdentity();
            } catch (JSchException ignored) {
                // 擦除失败仅属防御性操作，不影响已建立的会话
            }
        }
    }

    /** 查找指定主机当前活跃会话的 sessionId（无活跃会话返回 null） */
    public String findByHostId(long hostId) {
        for (Map.Entry<String, SshSession> e : sessions.entrySet()) {
            SshSession s = e.getValue();
            if (s.hostId == hostId && !s.disconnected && s.session.isConnected()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 列出当前所有活跃 SSH 会话（连接仍然有效）。
     *
     * <p>供前端「刷新/重开页面后对账」使用：浏览器侧只存主机选择、不存 sessionId，
     * 页面加载时调 {@code GET /api/ssh/active} 取回仍存活的会话并还原连接状态，
     * 无需用户重新输密码（会话空闲存活 30 分钟，见 {@link #reapIdle}）。
     * 同一主机理论上至多一个活跃会话，若存在多个取第一个。
     */
    public List<Map<String, Object>> listActive() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, SshSession> e : sessions.entrySet()) {
            SshSession s = e.getValue();
            if (s.disconnected || !s.session.isConnected()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", e.getKey());
            m.put("hostId", s.hostId);
            m.put("hostName", s.hostName);
            out.add(m);
        }
        return out;
    }

    /** 重置某会话的空闲计时（终端 WS 每次键盘输入调用），会话不存在时静默忽略 */
    public void touch(String sessionId) {
        SshSession s = sessions.get(sessionId);
        if (s != null && !s.disconnected) {
            s.lastActive = System.currentTimeMillis();
        }
    }

    /** 在已连接的会话上执行命令（非流式，等待完成） */
    public ExecResult exec(String sessionId, String command, int timeoutSec) throws ApiException {        SshSession s = require(sessionId);
        ChannelExec channel = null;
        long start = System.currentTimeMillis();
        try {
            channel = (ChannelExec) s.session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();
            channel.setErrStream(errStream);
            InputStream in = channel.getInputStream();

            int connectTimeout = Math.max(timeoutSec, 5) * 1000;
            channel.connect(connectTimeout);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Future<Void> reader = execPool.submit(() -> {
                byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } catch (IOException ignored) {
                    // 通道被关闭（超时/断开）时正常结束
                }
                return null;
            });

            boolean timedOut = false;
            try {
                reader.get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                timedOut = true;
                try { channel.disconnect(); } catch (Exception ignored) {}
                try { reader.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
            } catch (Exception e) {
                // 读取异常：返回已收集内容
            }

            int exit = timedOut ? -1 : channel.getExitStatus();
            String stderr = errStream.toString(StandardCharsets.UTF_8);
            if (timedOut) stderr = stderr + "\n[timeout]";
            return new ExecResult(exit, out.toString(StandardCharsets.UTF_8), stderr,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw channelFailure(s, "命令执行失败", e);
        } finally {
            if (channel != null) {
                try { channel.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 打开一个<b>不等待结束</b>的 exec 通道（长命令异步化用）。
     *
     * <p>与 {@link #exec} 的区别：命令在远程开始执行后立即返回 {@link ChannelExec}，
     * 通道生命周期、stdout/stderr 泵送、超时与退出码均由调用方（{@link SshJobManager}）管理。
     * 每次新开独立通道，不占用 SFTP 通道，也不与终端 shell 通道互相干扰。
     */
    public ChannelExec openExec(String sessionId, String command) throws ApiException {
        SshSession s = require(sessionId);
        try {
            ChannelExec channel = (ChannelExec) s.session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.connect(15_000);
            return channel;
        } catch (Exception e) {
            throw new ApiException(500, "异步命令通道打开失败：" + e.getMessage(), "SSH_EXEC_FAILED");
        }
    }

    /**
     * 打开交互式 shell 通道（终端 WebSocket 用，翻译自旧 NestJS 的 {@code ssh.service.ts openShell}）。
     *
     * <p>调用方拿到通道后自行绑定 stdin/stdout 与 WebSocket 双向桥接；
     * 通道生命周期由调用方管理（WS 关闭时 {@code channel.disconnect()}）。
     */
    public ChannelShell openShell(String sessionId) throws ApiException {
        SshSession s = require(sessionId);
        try {
            ChannelShell shell = (ChannelShell) s.session.openChannel("shell");
            shell.setPtyType("xterm-256color");
            return shell;
        } catch (Exception e) {
            throw new ApiException(500, "Shell 通道打开失败：" + e.getMessage(), "SSH_ERROR");
        }
    }

    /**
     * 返回该会话的 SFTP 通道串行锁。{@link SftpService} 的所有通道操作（ls/get/put/rm/
     * rename/mkdir/stat…）必须持此锁；exec 通道每次新开、互不干扰，不需要持锁。
     */
    public Object sftpLock(String sessionId) throws ApiException {
        return require(sessionId).sftpLock;
    }

    /** 打开（或复用）SFTP 通道。通道懒创建本身也在 sftpLock 内，避免并发时重复开通道。 */
    public ChannelSftp getSftp(String sessionId) throws ApiException {
        SshSession s = require(sessionId);
        synchronized (s.sftpLock) {
            ChannelSftp sftp = s.sftp;
            if (sftp != null && sftp.isConnected()) {
                return sftp;
            }
            try {
                ChannelSftp fresh = (ChannelSftp) s.session.openChannel("sftp");
                fresh.connect(20_000);
                s.sftp = fresh;
                return fresh;
            } catch (Exception e) {
                throw channelFailure(s, "SFTP 通道打开失败", e);
            }
        }
    }

    public boolean isSessionValid(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public Map<String, Object> getSessionInfo(String sessionId) {
        SshSession s = sessions.get(sessionId);
        if (s == null) return null;
        return Map.of("hostId", s.hostId, "hostName", s.hostName);
    }

    public void disconnect(String sessionId) {
        SshSession s = sessions.remove(sessionId);
        if (s == null) return;
        s.disconnected = true;
        if (s.sftp != null) {
            try { s.sftp.disconnect(); } catch (Exception ignored) {}
            s.sftp = null;
        }
        try { s.session.disconnect(); } catch (Exception ignored) {}
    }

    public void disconnectAll() {
        for (String id : sessions.keySet()) {
            disconnect(id);
        }
        execPool.shutdownNow();
        reaper.shutdownNow();
    }

    private SshSession require(String sessionId) {
        SshSession s = sessions.get(sessionId);
        if (s == null || s.disconnected) {
            // 文案含「尚未连接」：前端 HostConnectBlock 按该稳定中文弹出重连引导卡
            throw new ApiException(404,
                    "该主机当前尚未连接（SSH 会话不存在或已断开），请在右侧重新连接主机后继续。",
                    "SESSION_NOT_FOUND");
        }
        s.lastActive = System.currentTimeMillis();
        return s;
    }

    /**
     * 通道（exec / sftp）异常分类：底层连接已死时，把误导性的 "session is down" 翻译成
     * 「主机尚未连接」语义，前端据此弹出重连卡片；其余异常保留原始动作与原因。
     */
    private ApiException channelFailure(SshSession s, String action, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        boolean dead = s.disconnected || !s.session.isConnected()
                || lower.contains("session is down")
                || lower.contains("socket closed")
                || lower.contains("channel is not connected")
                || lower.contains("connection reset");
        if (dead) {
            s.disconnected = true;
            log.info("[SshSessionManager] 通道操作命中失效连接，标记会话待重连 hostName={} reason={}",
                    s.hostName, msg);
            return new ApiException(503,
                    "该主机当前尚未连接（SSH 连接已断开或失效：" + msg + "），请在右侧重新连接主机后继续。",
                    "HOST_SESSION_DOWN");
        }
        String code = action.startsWith("SFTP") ? "SFTP_ERROR" : "SSH_EXEC_FAILED";
        return new ApiException(500, action + "：" + msg, code);
    }

    /** 定时回收空闲超过 30 分钟的会话 */
    private void reapIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SshSession> e : sessions.entrySet()) {
            SshSession s = e.getValue();
            // JSch keepalive 已判定死亡（半开连接被发现）但本地记录还在：顺手清理
            if (!s.disconnected && !s.session.isConnected()) {
                log.info("[SshSessionManager] 检测到底层连接已失效，回收会话 sessionId={} hostName={}",
                        e.getKey(), s.hostName);
                disconnect(e.getKey());
                continue;
            }
            if (s.disconnected || now - s.lastActive <= IDLE_TIMEOUT_MS) {
                continue;
            }
            log.info("[SshSessionManager] 空闲超时回收会话 sessionId={} hostName={} idle={}s",
                    e.getKey(), s.hostName, TimeUnit.MILLISECONDS.toSeconds(now - s.lastActive));
            disconnect(e.getKey());
        }
    }

    /** 把 JSch 连接异常映射成带 code 的业务异常（对齐旧 NestJS 的 SSH_AUTH_FAILED / SSH_CONN_FAILED） */
    private static ApiException translateConnectError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("auth fail") || lower.contains("userauth fail")
                || lower.contains("authentication methods failed")) {
            return new ApiException(401,
                    "SSH 认证失败，请检查用户名、密码或私钥是否正确，以及服务器是否允许该认证方式",
                    "SSH_AUTH_FAILED");
        }
        if (lower.contains("connectexception") || lower.contains("connection refused")
                || lower.contains("connection reset") || lower.contains("timed out")
                || lower.contains("timeout") || lower.contains("unreachable")) {
            return new ApiException(400, "无法连接到服务器，请检查主机地址、端口和网络是否可达", "SSH_CONN_FAILED");
        }
        return new ApiException(400, "SSH 连接失败：" + msg, "SSH_ERROR");
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    /** 128bit 强随机后缀（SecureRandom，base64url 无填充） */
    private String secureSuffix() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}