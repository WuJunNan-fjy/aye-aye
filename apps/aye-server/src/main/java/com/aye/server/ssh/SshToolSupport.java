package com.aye.server.ssh;

import com.aye.server.storage.HostStore;
import com.aye.server.web.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * SSH / SFTP AI 工具的公共逻辑：按稳定的 {@code host_key} 解析出可用的会话。
 *
 * <p>凭据不落库（密码/私钥只在 {@link SshSessionManager} 内存中，密码经 Electron safeStorage
 * 加密存在桌面端），因此解析顺序：
 * <ol>
 *   <li>优先复用该 hostId 已建立的活跃会话（任何认证类型都适用）；</li>
 *   <li>无活跃会话时，仅「私钥认证 + 配置了 privateKeyPath」能自动建连（读私钥文件）；</li>
 *   <li>密码认证且未连接 → 明确报错，提示用户先在前端连接一次。</li>
 * </ol>
 */
public final class SshToolSupport {

    /** AI 工具单文件读写上限（1MB）：超过请走前端 SFTP 组件 / HTTP /api/sftp 接口 */
    public static final long MAX_FILE_BYTES = 1024L * 1024;

    private final HostStore hostStore;
    private final SshSessionManager manager;

    public SshToolSupport(HostStore hostStore, SshSessionManager manager) {
        this.hostStore = hostStore;
        this.manager = manager;
    }

    /** 将 AI 使用的稳定 host_key 解析为内部数据库 id。 */
    public long resolveHostId(String hostKey) throws ApiException {
        if (hostKey == null || hostKey.isBlank()) {
            throw new ApiException(400, "host_key 不能为空，请先调用 list_hosts", "HOST_KEY_REQUIRED");
        }
        Map<String, Object> host = hostStore.findOneByKey(hostKey.trim());
        if (host == null) {
            throw new ApiException(404, "主机不存在或 host_key 无效：" + hostKey, "HOST_NOT_FOUND");
        }
        return ((Number) host.get("id")).longValue();
    }

    /** 解析 host_key 对应的可用 sessionId。 */
    public String resolveSession(String hostKey) throws ApiException {
        return resolveSession(resolveHostId(hostKey));
    }

    /** 解析内部 id 对应的可用 sessionId，供前端连接 API 使用。 */
    public String resolveSession(long hostId) throws ApiException {
        Map<String, Object> host = hostStore.findOne(hostId);
        if (host == null) {
            throw new ApiException(404, "主机不存在：" + hostId, "HOST_NOT_FOUND");
        }

        // 1. 复用已有活跃会话（密码/私钥都在内存里，任何认证类型都适用）
        String existing = manager.findByHostId(hostId);
        if (existing != null) {
            return existing;
        }

        // 2. 无活跃会话：仅私钥认证且配了私钥文件路径时可自动建连
        if ("PRIVATE_KEY".equals(host.get("authType"))) {
            String keyPath = strOrNull(host.get("privateKeyPath"));
            if (keyPath != null && !keyPath.isBlank()) {
                SshSessionManager.ConnectResult r =
                        manager.connect(hostId, Map.of("privateKey", readPrivateKey(keyPath)));
                if (!"connected".equals(r.status())) {
                    // 主机密钥 TOFU 待确认：无法自动信任，引导用户先去主机管理页确认指纹
                    throw new ApiException(400,
                            "该主机密钥指纹尚未被信任（" + safeFingerprint(r) + "）。"
                                    + "请先在主机管理页手动连接一次并确认主机指纹，之后即可自动建连。",
                            "HOST_KEY_NOT_TRUSTED");
                }
                return r.sessionId();
            }
        }

        // 3. 密码认证（密码不落库）/ 私钥无文件路径 → 无法自动取凭据
        throw new ApiException(400,
                "该主机尚未连接（凭据不落库，无法自动获取）。请先在主机管理页连接一次，"
                        + "或为该主机配置私钥文件路径后重试。",
                "HOST_NOT_CONNECTED");
    }

    /** 读取私钥文件（PEM 文本） */
    private static String readPrivateKey(String path) throws ApiException {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                throw new ApiException(400, "私钥文件不存在：" + path, "PRIVATE_KEY_NOT_FOUND");
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "读取私钥文件失败：" + e.getMessage(), "PRIVATE_KEY_READ_FAILED");
        }
    }

    /**
     * 写后验证提示（<b>软提示，非强制</b>）：在写/改/传类工具成功结果后追加一行建议，
     * 引导 Agent 自行回读/语法检查。仅提示、无校验门槛、无重试循环——
     * 经验：强制 effect-evidence 闭环会放大轮次/token 并诱发「降智/卡住」，故只做非阻塞建议。
     */
    public static final String VERIFY_HINT_WRITE =
            "建议验证：用 sftp_read 回读关键内容确认写入正确；若是配置文件，"
                    + "先做语法检查（如 nginx -t、对应服务 --check/configtest）再 reload。";
    public static final String VERIFY_HINT_EDIT =
            "建议验证：回读改动处确认替换符合预期；配置类文件先做语法检查再 reload，避免坏配置生效。";
    public static final String VERIFY_HINT_UPLOAD =
            "建议验证：用 run_ssh 检查目标文件（ls -l、校验和，或直接加载/试运行）确认上传完整可用。";

    public static String withVerifyHint(String text, String hint) {
        if (hint == null || hint.isBlank()) return text;
        return text + "\n" + hint;
    }

    /** 字节数转人类可读（B / KB / MB / GB） */
    public static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1024.0 / 1024.0);
        return String.format("%.1f GB", n / 1024.0 / 1024.0 / 1024.0);
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    /** 展示指纹，缺失时空串 */
    private static String safeFingerprint(SshSessionManager.ConnectResult r) {
        return r.fingerprint() == null ? "" : ("SHA256:" + r.fingerprint());
    }
}
