package com.aye.server.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 应用设置存储（SQLite 的 {@code app_settings} 表，key-value 结构）。
 *
 * <p>翻译自旧 NestJS 的 {@code settings.service.ts}，并兼容前端 {@code settingsApi.ts}
 * 里新增的 {@code autoAcceptPermissions / notifications / keybinds} 字段。
 * 标量存字符串、对象存 JSON，读取时按 key 还原类型。
 */
public final class SettingsStore {

    /** 默认设置（与前端 useSettingsStore 的 DEFAULT_SETTINGS 保持一致） */
    private static final Map<String, Object> DEFAULTS = defaultSettings();

    private final Database db;

    public SettingsStore(Database db) {
        this.db = db;
    }

    /** 合并默认值后返回完整设置对象 */
    public Map<String, Object> getAll() {
        Map<String, Object> settings = new LinkedHashMap<>(DEFAULTS);
        for (Map<String, Object> row : db.query("SELECT key, value FROM app_settings")) {
            String key = String.valueOf(row.get("key"));
            Object value = row.get("value");
            if (value == null) continue;
            settings.put(key, restore(key, value.toString()));
        }
        return settings;
    }

    /** 部分更新（只写 patch 里非 null 的 key），返回更新后的完整设置 */
    public Map<String, Object> update(Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value == null) continue;
            validate(key, value);
            db.update("""
                            INSERT INTO app_settings (key, value, updated_at)
                            VALUES (?, ?, datetime('now', 'localtime'))
                            ON CONFLICT(key) DO UPDATE SET value = excluded.value,
                                                          updated_at = datetime('now', 'localtime')""",
                    key, store(value));
        }
        return getAll();
    }

    /** 重置所有设置为默认值 */
    public Map<String, Object> reset() {
        db.update("DELETE FROM app_settings");
        return getAll();
    }

    /** 执行模式持久化的读取口（供 /agent/mode 使用） */
    public String getExecutionMode() {
        return String.valueOf(getAll().get("defaultExecutionMode"));
    }

    public void setExecutionMode(String mode) {
        update(Map.of("defaultExecutionMode", mode));
    }

    private static Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultExecutionMode", "write_confirm");
        m.put("terminalFontSize", 14);
        m.put("terminalFontFamily", "JetBrains Mono, Menlo, Monaco, Consolas, monospace");
        m.put("terminalScrollback", 10000);
        m.put("terminalTheme", "dark");
        m.put("language", "zh-CN");
        m.put("autoAcceptPermissions", false);
        m.put("webSearch", Map.of("provider", "AUTO", "apiKey", ""));
        m.put("notifications", Map.of("agent", true, "permissions", true, "errors", true));
        m.put("keybinds", Map.of(
                "toggle-terminal", "Ctrl+`",
                "new-connection", "Ctrl+N",
                "close-tab", "Ctrl+W",
                "settings", "Ctrl+,",
                "toggle-theme", "Ctrl+Shift+T"));
        return m;
    }

    private static final Set<String> INT_KEYS = Set.of("terminalFontSize", "terminalScrollback");
    private static final Set<String> BOOL_KEYS = Set.of("autoAcceptPermissions");
    private static final Set<String> OBJECT_KEYS = Set.of("notifications", "keybinds", "webSearch");

    private static String store(Object value) {
        if (value instanceof Map || value instanceof java.util.List) return Json.stringify(value);
        return String.valueOf(value);
    }

    private static Object restore(String key, String raw) {
        if (INT_KEYS.contains(key)) {
            try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return raw; }
        }
        if (BOOL_KEYS.contains(key)) {
            return Boolean.parseBoolean(raw);
        }
        if (OBJECT_KEYS.contains(key)) {
            try {
                JsonNode node = Json.parse(raw);
                return Json.MAPPER.convertValue(node, Object.class);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    private static void validate(String key, Object value) {
        switch (key) {
            case "defaultExecutionMode" -> {
                if (!Set.of("readonly", "write_confirm", "all_confirm").contains(String.valueOf(value))) {
                    throw new IllegalArgumentException("无效的执行模式: " + value);
                }
            }
            case "terminalFontSize" -> {
                int n = ((Number) value).intValue();
                if (n < 10 || n > 32) throw new IllegalArgumentException("终端字号必须在 10-32 之间");
            }
            case "terminalScrollback" -> {
                int n = ((Number) value).intValue();
                if (n < 100 || n > 100000) throw new IllegalArgumentException("终端回滚行数必须在 100-100000 之间");
            }
            case "terminalTheme" -> {
                if (!Set.of("dark", "light", "auto").contains(String.valueOf(value))) {
                    throw new IllegalArgumentException("无效的终端主题: " + value);
                }
            }
            case "language" -> {
                if (!Set.of("zh-CN", "en-US").contains(String.valueOf(value))) {
                    throw new IllegalArgumentException("无效的语言: " + value);
                }
            }
            default -> { /* autoAcceptPermissions / notifications / keybinds 不额外校验 */ }
        }
    }
}
