package com.aye.server.controller;

import com.aye.server.chat.ChatRequest;
import com.aye.server.chat.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent v2 接口：Agent turn 与 HTTP 连接完全解耦。
 *
 * <p>POST /api/v2/agent/turns —— 同步校验通过后返回 202 {@code {sessionId, turnNo}}，
 * agent 在服务端后台运行，事件只写事件日志（前端经 GET /api/sessions/{id}/events 订阅），
 * 启动请求断开/页面切换/刷新都不影响 agent。
 *
 * <p>POST /api/v2/agent/cancel —— 显式停止，幂等：无运行任务时返回 {@code ok:false}（HTTP 200）。
 */
@RestController
@RequestMapping("/api/v2/agent")
public class AgentV2Controller {

    private final ChatService chatService;

    public AgentV2Controller(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/turns")
    public ResponseEntity<Map<String, Object>> startTurn(@RequestBody ChatRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        ChatService.StartResult result = chatService.start(req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", result.sessionId());
        body.put("turnNo", result.turnNo());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        boolean ok = chatService.cancel(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        if (!ok) {
            out.put("message", "该会话当前没有运行中的任务");
        }
        return out;
    }
}
