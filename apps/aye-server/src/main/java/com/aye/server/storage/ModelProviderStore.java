package com.aye.server.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型厂商配置存储（provider 中心制，对标 deepseek-harness 的 settings 模型）。
 *
 * <p>数据模型：一个厂商一条配置（{@code model_providers}），key 单独存
 * {@code provider_keys}（write-only，永不回传明文），每个厂商维护自己的
 * 模型目录（{@code provider_models}），全局默认模型记在 {@code model_default}。
 *
 * <p>首次启动自动从旧版 {@code models} 表（模型实例制）迁移：按 provider 归组
 * 建厂商、key 取该厂商第一条非空、目录逐条转入、默认模型对应迁移，随后删旧表。
 */
public final class ModelProviderStore {

    /** 目录模型项（capacity/thinking 为 v2 高级字段，可空） */
    public record CatalogModel(String modelId, String name, Integer contextWindow, Integer maxTokens, Boolean thinking) {}

    /** 厂商完整配置（apiKey 仅内部使用，不直接出 JSON；displayName 可空 = 用厂商展示名） */
    public record Provider(String id, String displayName, String baseUrl, boolean enabled, String apiKey, List<CatalogModel> models) {}

    /** 默认模型引用 */
    public record DefaultRef(String providerId, String modelId) {}

    private final Database db;

    public ModelProviderStore(Database db) {
        this.db = db;
        initTables();
        migrateLegacyModels();
    }

    private void initTables() {
        db.update("""
                CREATE TABLE IF NOT EXISTS model_providers (
                    id           TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL DEFAULT '',
                    base_url     TEXT NOT NULL DEFAULT '',
                    enabled      INTEGER NOT NULL DEFAULT 1,
                    created_at   INTEGER,
                    updated_at   INTEGER
                )""");
        db.update("""
                CREATE TABLE IF NOT EXISTS provider_keys (
                    provider_id TEXT PRIMARY KEY,
                    api_key     TEXT NOT NULL
                )""");
        db.update("""
                CREATE TABLE IF NOT EXISTS provider_models (
                    provider_id    TEXT NOT NULL,
                    model_id       TEXT NOT NULL,
                    name           TEXT NOT NULL DEFAULT '',
                    context_window INTEGER,
                    max_tokens     INTEGER,
                    thinking       INTEGER,
                    sort           INTEGER NOT NULL DEFAULT 0,
                    created_at     INTEGER,
                    PRIMARY KEY (provider_id, model_id)
                )""");
        db.update("""
                CREATE TABLE IF NOT EXISTS model_default (
                    id          INTEGER PRIMARY KEY CHECK (id = 1),
                    provider_id TEXT NOT NULL,
                    model_id    TEXT NOT NULL
                )""");
        // 存量库缺列补齐（ALTER TABLE ADD COLUMN；新列一律可空，兼容旧行）
        ensureColumn("model_providers", "display_name", "TEXT");
        ensureColumn("provider_models", "context_window", "INTEGER");
        ensureColumn("provider_models", "max_tokens", "INTEGER");
        ensureColumn("provider_models", "thinking", "INTEGER");
    }

    /** PRAGMA table_info + ALTER TABLE ADD COLUMN：旧库缺列时补齐。 */
    private void ensureColumn(String table, String column, String type) {
        List<Map<String, Object>> cols = db.query("PRAGMA table_info(" + table + ")");
        boolean has = cols.stream().anyMatch(r -> column.equalsIgnoreCase(str(r.get("name"))));
        if (!has) db.update("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    // ── 查询 ──

    /** 全部厂商配置（按厂商目录插入顺序） */
    public List<Provider> listProviders() {
        List<Provider> out = new ArrayList<>();
        for (Map<String, Object> row : db.query("SELECT * FROM model_providers ORDER BY created_at ASC")) {
            String id = str(row.get("id"));
            out.add(new Provider(id, str(row.get("display_name")), str(row.get("base_url")), num(row.get("enabled")) != 0,
                    apiKey(id), listModels(id)));
        }
        return out;
    }

    public Provider get(String id) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM model_providers WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Provider(id, str(row.get("display_name")), str(row.get("base_url")), num(row.get("enabled")) != 0,
                apiKey(id), listModels(id));
    }

    public List<CatalogModel> listModels(String providerId) {
        List<CatalogModel> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(
                "SELECT model_id, name, context_window, max_tokens, thinking FROM provider_models "
                        + "WHERE provider_id = ? ORDER BY sort ASC, created_at ASC", providerId)) {
            out.add(new CatalogModel(str(r.get("model_id")), str(r.get("name")),
                    intOrNull(r.get("context_window")), intOrNull(r.get("max_tokens")), boolOrNull(r.get("thinking"))));
        }
        return out;
    }

    /** 某厂商的 key（仅内部使用） */
    public String apiKey(String providerId) {
        List<Map<String, Object>> rows = db.query("SELECT api_key FROM provider_keys WHERE provider_id = ?", providerId);
        return rows.isEmpty() ? "" : str(rows.get(0).get("api_key"));
    }

    /** 默认模型：显式设置 > 第一个启用厂商的第一个目录模型 > null */
    public DefaultRef getDefaultRef() {
        List<Map<String, Object>> rows = db.query("SELECT provider_id, model_id FROM model_default WHERE id = 1");
        if (!rows.isEmpty()) {
            String pid = str(rows.get(0).get("provider_id"));
            String mid = str(rows.get(0).get("model_id"));
            if (get(pid) != null && hasModel(pid, mid)) return new DefaultRef(pid, mid);
        }
        for (Provider p : listProviders()) {
            if (p.enabled() && !p.models().isEmpty()) {
                return new DefaultRef(p.id(), p.models().get(0).modelId());
            }
        }
        return null;
    }

    public boolean hasModel(String providerId, String modelId) {
        return !db.query("SELECT 1 FROM provider_models WHERE provider_id = ? AND model_id = ?",
                providerId, modelId).isEmpty();
    }

    // ── 写入 ──

    /** 新建/更新厂商配置（displayName/baseUrl/enabled 传 null 表示保持不变） */
    public Provider upsertProvider(String id, String displayName, String baseUrl, Boolean enabled) {
        long now = System.currentTimeMillis();
        if (get(id) == null) {
            db.update("INSERT INTO model_providers (id, display_name, base_url, enabled, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    id, displayName == null ? "" : displayName.trim(),
                    baseUrl == null ? "" : baseUrl.trim(), enabled == null || enabled ? 1 : 0, now, now);
        } else {
            db.update("UPDATE model_providers SET display_name = COALESCE(?, display_name), "
                            + "base_url = COALESCE(?, base_url), enabled = COALESCE(?, enabled), updated_at = ? WHERE id = ?",
                    displayName == null ? null : displayName.trim(),
                    baseUrl == null ? null : baseUrl.trim(), enabled, now, id);
        }
        return get(id);
    }

    public boolean deleteProvider(String id) {
        if (db.update("DELETE FROM model_providers WHERE id = ?", id) == 0) return false;
        db.update("DELETE FROM provider_keys WHERE provider_id = ?", id);
        db.update("DELETE FROM provider_models WHERE provider_id = ?", id);
        DefaultRef d = getDefaultRef();
        if (d != null && id.equals(d.providerId())) {
            db.update("DELETE FROM model_default WHERE id = 1");
        }
        return true;
    }

    /** 设置/更换 key；传空串 = 清除 */
    public void setKey(String providerId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            db.update("DELETE FROM provider_keys WHERE provider_id = ?", providerId);
        } else {
            db.update("INSERT INTO provider_keys (provider_id, api_key) VALUES (?, ?) "
                    + "ON CONFLICT(provider_id) DO UPDATE SET api_key = excluded.api_key", providerId, apiKey.trim());
        }
    }

    /** 向厂商目录添加/更新模型（容量/思考传 null 表示清除） */
    public void addModel(String providerId, String modelId, String name,
                         Integer contextWindow, Integer maxTokens, Boolean thinking) {
        long now = System.currentTimeMillis();
        Integer sort = db.query("SELECT COALESCE(MAX(sort), -1) + 1 AS next FROM provider_models WHERE provider_id = ?", providerId)
                .stream().findFirst().map(r -> (int) num(r.get("next"))).orElse(0);
        db.update("""
                        INSERT INTO provider_models (provider_id, model_id, name, context_window, max_tokens, thinking, sort, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(provider_id, model_id) DO UPDATE SET
                            name = excluded.name,
                            context_window = excluded.context_window,
                            max_tokens = excluded.max_tokens,
                            thinking = excluded.thinking
                        """,
                providerId, modelId, name == null ? "" : name.trim(),
                contextWindow, maxTokens, thinking == null ? null : (thinking ? 1 : 0), sort, now);
    }

    /** 从目录删除模型；若它是默认模型则一并清掉默认引用 */
    public void removeModel(String providerId, String modelId) {
        db.update("DELETE FROM provider_models WHERE provider_id = ? AND model_id = ?", providerId, modelId);
        DefaultRef d = getDefaultRef();
        if (d != null && providerId.equals(d.providerId()) && modelId.equals(d.modelId())) {
            db.update("DELETE FROM model_default WHERE id = 1");
        }
    }

    public void setDefault(String providerId, String modelId) {
        db.update("INSERT INTO model_default (id, provider_id, model_id) VALUES (1, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET provider_id = excluded.provider_id, model_id = excluded.model_id",
                providerId, modelId);
    }

    // ── 旧表迁移 ──

    private void migrateLegacyModels() {
        List<Map<String, Object>> exists = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'models'");
        if (exists.isEmpty()) return;
        List<Map<String, Object>> legacy = db.query("SELECT * FROM models ORDER BY created_at ASC");
        Map<String, String> providerBaseUrl = new LinkedHashMap<>();
        Map<String, String> providerKey = new LinkedHashMap<>();
        String legacyDefault = null;
        for (Map<String, Object> r : legacy) {
            String provider = str(r.get("provider"));
            String modelId = str(r.get("model_id"));
            if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()) continue;
            providerBaseUrl.putIfAbsent(provider, str(r.get("base_url")));
            String key = str(r.get("api_key"));
            if (key != null && !key.isBlank()) providerKey.putIfAbsent(provider, key);
            upsertProvider(provider, null, providerBaseUrl.get(provider), null);
            addModel(provider, modelId, str(r.get("name")), null, null, null);
            if (num(r.get("is_default")) != 0) legacyDefault = provider + "::" + modelId;
        }
        for (Map.Entry<String, String> e : providerKey.entrySet()) {
            setKey(e.getKey(), e.getValue());
        }
        if (legacyDefault != null) {
            String[] parts = legacyDefault.split("::", 2);
            if (parts.length == 2 && hasModel(parts[0], parts[1])) setDefault(parts[0], parts[1]);
        }
        db.update("DROP TABLE models");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String s = o.toString();
        return s.isEmpty() ? null : Integer.parseInt(s);
    }

    private static Boolean boolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(o.toString());
    }
}
