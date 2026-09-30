package com.aye.server.controller;

import com.aye.server.storage.SettingsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 执行模式接口（对应前端 {@code agentApi.ts} 的 getExecutionMode / setExecutionMode）。
 *
 * <p>模式持久化到设置里的 {@code defaultExecutionMode}，与「设置 → 执行模式」页保持一致。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentModeController {

    private final SettingsStore settings;

    public AgentModeController(SettingsStore settings) {
        this.settings = settings;
    }

    @GetMapping("/mode")
    public Map<String, Object> getMode() {
        return Map.of("mode", settings.getExecutionMode());
    }

    @PutMapping("/mode")
    public Map<String, Object> setMode(@RequestBody Map<String, Object> body) {
        String mode = body.get("mode") == null ? "write_confirm" : String.valueOf(body.get("mode"));
        settings.setExecutionMode(mode);
        return Map.of("success", true);
    }
}
