package com.aye.server.storage;

import java.util.List;
import java.util.Map;

/**
 * SSH 主机密钥 TOFU 存储（P0-4）——对应 OpenSSH 的 {@code known_hosts}。
 *
 * <p>按 {@code (address, port)} 唯一索引保存首次见过的服务器公钥指纹（SHA-256）。
 * 连接时：无记录 → TOFU 首连需用户信任；有记录且指纹一致 → 放行；有记录但指纹不同 →
 * 判定中间人，拒绝。解决 {@code StrictHostKeyChecking=no} 无法抵御 MITM 的问题。
 */
public final class KnownHostsStore {

    private final Database db;

    public KnownHostsStore(Database db) {
        this.db = db;
    }

    /** 查询某主机的已信任指纹；无记录返回 null */
    public String fingerprintOf(String address, int port) {
        List<Map<String, Object>> rows = db.query(
                "SELECT fingerprint FROM ssh_known_hosts WHERE address = ? AND port = ?", address, port);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("fingerprint"));
    }

    /** 首次信任（TOFU）：记录主机指纹 */
    public void trust(
            String hostKey, String address, int port, String fingerprint) {
        db.update("""
                        INSERT INTO ssh_known_hosts (host_key, address, port, fingerprint)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(address, port) DO UPDATE SET
                          fingerprint = excluded.fingerprint,
                          host_key    = excluded.host_key""",
                hostKey, address, port, fingerprint);
    }

    /** 撤销信任（删除 known_hosts 记录） */
    public void untrust(String address, int port) {
        db.update("DELETE FROM ssh_known_hosts WHERE address = ? AND port = ?", address, port);
    }

    /** 全部已信任指纹（前端展示用） */
    public List<Map<String, Object>> all() {
        return db.query(
                "SELECT id, address, port, fingerprint, trust_type, created_at "
                        + "FROM ssh_known_hosts ORDER BY created_at DESC");
    }
}