package com.aye.server.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.aye.server.storage.KnownHostsStore;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 主机密钥 TOFU 校验仓库（P0-4）。
 *
 * <p>替换 JSch 默认的 {@code KnownHosts}，读写 {@link KnownHostsStore}：
 * 已信任且指纹一致 → 放行；未见过 → 记为 {@link CheckOutcome#NEW}；指纹不符 →
 * 记为 {@link CheckOutcome#CHANGED}（判定中间人）。
 *
 * <p>每个连接实例一个仓库（{@link SshSessionManager#connect} 内创建），
 * 通过 {@link #bind(String, int)} 绑定当前连接的目标端点，因为 JSch 的
 * {@code check(String host, byte[] key)} 回调只带主机名、不带端口。
 * 连接异常后由 {@link #lastOutcome()} / {@link #lastFingerprint()} 供上层决定是否需信任确认。
 */
public final class TofuHostKeyRepository implements HostKeyRepository {

    /** 最近一次 check 的结果 */
    public enum CheckOutcome { OK, NEW, CHANGED }

    private final KnownHostsStore store;
    private volatile String address;
    private volatile int port;
    private volatile CheckOutcome lastOutcome = CheckOutcome.OK;
    private volatile String lastFingerprint;

    public TofuHostKeyRepository(KnownHostsStore store) {
        this.store = store;
    }

    /** 连接前绑定目标端点，并重置上一次结果 */
    public void bind(String address, int port) {
        this.address = address;
        this.port = port;
        this.lastOutcome = CheckOutcome.OK;
        this.lastFingerprint = null;
    }

    public CheckOutcome lastOutcome() {
        return lastOutcome;
    }

    /** 最近一次被检查主机密钥的 SHA-256 指纹（供前端展示 / 信任写入） */
    public String lastFingerprint() {
        return lastFingerprint;
    }

    @Override
    public int check(String ignoredHost, byte[] key) {
        String stored = store.fingerprintOf(address, port);
        String offered = fingerprintOf(key);
        lastFingerprint = offered;
        if (stored == null) {
            lastOutcome = CheckOutcome.NEW;
            return NOT_INCLUDED;
        }
        if (stored.equals(offered)) {
            lastOutcome = CheckOutcome.OK;
            return OK;
        }
        lastOutcome = CheckOutcome.CHANGED;
        return CHANGED;
    }

    @Override
    public void add(HostKey hostkey, com.jcraft.jsch.UserInfo ui) {
        // TOFU 信任走 KnownHostsStore.trust，不依赖 JSch 回调
    }

    @Override
    public void remove(String host, String type) {
        // 撤销走 KnownHostsStore.untrust
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        // 同上
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return "aye-known-hosts";
    }

    @Override
    public HostKey[] getHostKey() {
        return new HostKey[0];
    }

    @Override
    public HostKey[] getHostKey(String host, String type) {
        return new HostKey[0];
    }

    private static String fingerprintOf(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}