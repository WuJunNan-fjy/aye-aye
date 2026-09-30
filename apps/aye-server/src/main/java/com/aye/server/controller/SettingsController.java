package com.aye.server.controller;

import com.aye.server.storage.SettingsStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 应用设置接口（对应前端 {@code settingsApi.ts}）。翻译自旧 NestJS {@code settings.controller.ts}。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsStore store;

    public SettingsController(SettingsStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> get() {
        return Map.of("success", true, "settings", store.getAll());
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        return Map.of("success", true, "settings", store.update(body));
    }

    @DeleteMapping
    public Map<String, Object> reset() {
        return Map.of("success", true, "settings", store.reset());
    }
}
