package com.aye.server.controller;

import com.aye.server.ssh.SshSessionManager;
import com.aye.server.storage.KnownHostsStore;
import com.aye.server.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SSH 会话接口（对应前端 {@code sshApi.ts}）。翻译自旧 NestJS {@code ssh.controller.ts}。
 *
 * <p>含主机密钥 TOFU：{@code /connect} 首连返回 {@code need_trust}，前端经
 * {@code /trust} 确认信任后重连。
 */
@RestController
@RequestMapping("/api/ssh")
public class SshController {

    private final SshSessionManager manager;
    private final KnownHostsStore knownHostsStore;

    public SshController(SshSessionManager manager, KnownHostsStore knownHostsStore) {
        this.manager = manager;
        this.knownHostsStore = knownHostsStore;
    }

    @PostMapping("/connect/{hostId}")
    public SshSessionManager.ConnectResult connect(@PathVariable long hostId,
                                                   @RequestBody Map<String, Object> credentials) {
        return manager.connect(hostId, credentials);
    }

    /**
     * 当前仍活跃的 SSH 会话列表。前端刷新/重开页面后据此对账：
     * 浏览器不持久化 sessionId/凭据，只持久化主机选择，存活会话直接恢复为「已连接」。
     */
    @GetMapping("/active")
    public Map<String, Object> active() {
        return Map.of("sessions", manager.listActive());
    }

    @PostMapping("/exec/{sessionId}")
    public SshSessionManager.ExecResult exec(@PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String command = body.get("command") == null ? "" : String.valueOf(body.get("command"));
        if (command.isBlank()) {
            throw new ApiException(400, "command 不能为空", "BAD_REQUEST");
        }
        int timeout = body.get("timeout") != null ? ((Number) body.get("timeout")).intValue() : 30;
        return manager.exec(sessionId, command, timeout);
    }

    @PostMapping("/disconnect/{sessionId}")
    public Map<String, Object> disconnect(@PathVariable String sessionId) {
        manager.disconnect(sessionId);
        return Map.of("status", "disconnected");
    }

    /** TOFU：确认信任某主机密钥指纹 */
    @PostMapping("/trust")
    public Map<String, Object> trust(@RequestBody Map<String, Object> body) {
        String address = String.valueOf(body.get("address"));
        int port = body.get("port") != null ? ((Number) body.get("port")).intValue() : 22;
        String fingerprint = String.valueOf(body.get("fingerprint"));
        if (address.isBlank() || fingerprint.isBlank()) {
            throw new ApiException(400, "address 和 fingerprint 不能为空", "BAD_REQUEST");
        }
        knownHostsStore.trust(fingerprint, address, port, fingerprint);
        return Map.of("trusted", true, "address", address, "port", port);
    }

    /** 撤销已信任的主机密钥（不再放行该指纹） */
    @PostMapping("/untrust")
    public Map<String, Object> untrust(@RequestBody Map<String, Object> body) {
        String address = String.valueOf(body.get("address"));
        int port = body.get("port") != null ? ((Number) body.get("port")).intValue() : 22;
        knownHostsStore.untrust(address, port);
        return Map.of("untrusted", true, "address", address, "port", port);
    }

    /** 当前已信任的主机指纹列表 */
    @GetMapping("/known-hosts")
    public java.util.List<Map<String, Object>> knownHosts() {
        return knownHostsStore.all();
    }
}