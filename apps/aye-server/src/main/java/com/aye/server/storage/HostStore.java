package com.aye.server.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主机配置存储（SQLite 的 {@code hosts} 表）。
 *
 * <p>翻译自旧 NestJS 的 {@code host.service.ts}：CRUD + 排序 + 事务批量 reorder。
 * 字段命名对齐前端 {@code hostApi.ts} 的 {@code Host}（camelCase）。
 */
public final class HostStore {

    private final Database db;

    public HostStore(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> findAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : db.query("SELECT * FROM hosts ORDER BY sort_order ASC, id DESC")) {
            out.add(toView(row));
        }
        return out;
    }

    public Map<String, Object> findOne(long id) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM hosts WHERE id = ?", id);
        return rows.isEmpty() ? null : toView(rows.get(0));
    }

    public Map<String, Object> findOneByKey(String hostKey) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM hosts WHERE host_key = ?", hostKey);
        return rows.isEmpty() ? null : toView(rows.get(0));
    }

    /** 创建主机，返回完整视图（含内部 id、稳定 hostKey / sortOrder） */
    public Map<String, Object> create(Map<String, Object> host) {
        int maxOrder = maxOrder();
        db.update("""
                        INSERT INTO hosts (host_key, name, address, port, username, auth_type, credential_id,
                                           private_key_path, description, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                newHostKey(), str(host, "name"), str(host, "address"),
                host.containsKey("port") && host.get("port") != null ? (int) num(host.get("port")) : 22,
                str(host, "username"),
                strOr(host, "authType", "PASSWORD"),
                strOrNull(host, "credentialId"),
                strOrNull(host, "privateKeyPath"),
                strOrNull(host, "description"),
                host.containsKey("sortOrder") && host.get("sortOrder") != null ? (int) num(host.get("sortOrder")) : maxOrder + 1);
        return findOne(db.lastInsertRowId());
    }

    public Map<String, Object> update(long id, Map<String, Object> host) {
        if (findOne(id) == null) return null;
        db.update("""
                        UPDATE hosts SET
                          name = COALESCE(?, name),
                          address = COALESCE(?, address),
                          port = COALESCE(?, port),
                          username = COALESCE(?, username),
                          auth_type = COALESCE(?, auth_type),
                          credential_id = COALESCE(?, credential_id),
                          private_key_path = COALESCE(?, private_key_path),
                          description = COALESCE(?, description)
                        WHERE id = ?""",
                strOrNull(host, "name"), strOrNull(host, "address"),
                host.get("port") == null ? null : (int) num(host.get("port")),
                strOrNull(host, "username"),
                strOrNull(host, "authType"),
                strOrNull(host, "credentialId"),
                strOrNull(host, "privateKeyPath"),
                strOrNull(host, "description"),
                id);
        return findOne(id);
    }

    public boolean remove(long id) {
        return db.update("DELETE FROM hosts WHERE id = ?", id) > 0;
    }

    /** 按给定 id 顺序重排 sort_order（事务） */
    public void reorder(List<Long> orderedIds) {
        db.transaction(() -> {
            for (int i = 0; i < orderedIds.size(); i++) {
                db.update("UPDATE hosts SET sort_order = ? WHERE id = ?", i, orderedIds.get(i));
            }
        });
    }

    private int maxOrder() {
        List<Map<String, Object>> rows = db.query("SELECT COALESCE(MAX(sort_order), -1) AS m FROM hosts");
        Object v = rows.isEmpty() ? null : rows.get(0).get("m");
        return v == null ? -1 : (int) num(v);
    }

    /** 输出给前端的视图（camelCase，createdAt 为字符串） */
    private static Map<String, Object> toView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", num(r.get("id")));
        m.put("hostKey", r.get("host_key"));
        m.put("name", r.get("name"));
        m.put("address", r.get("address"));
        m.put("port", num(r.get("port")));
        m.put("username", r.get("username"));
        m.put("authType", r.get("auth_type"));
        m.put("credentialId", r.get("credential_id"));
        m.put("privateKeyPath", r.get("private_key_path"));
        m.put("description", r.get("description"));
        m.put("sortOrder", num(r.get("sort_order")));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    private static String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    private static String strOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return (long) Double.parseDouble(o.toString());
    }

    private static String newHostKey() {
        return "host_" + UUID.randomUUID().toString().replace("-", "");
    }
}
