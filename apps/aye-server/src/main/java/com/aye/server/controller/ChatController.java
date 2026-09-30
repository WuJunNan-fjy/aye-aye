package com.aye.server.controller;

import com.aye.server.chat.ChatRequest;
import com.aye.server.chat.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 聊天接口。
 *
 * <p>POST /api/agent/chat/stream —— SSE 流式返回，事件格式见 {@code AgentEventJson}：
 * {@code data: {"type":"text"|"thinking"|"tool_call"|"tool_result"|"error"|"done", ...}}。
 *
 * <p>POST /api/agent/interject —— 中途插话：Agent 运行中发送指示，缓冲至步边界生效
 * （协作式，不打断进行中的流式输出/工具执行）；停止/取消仍走 SSE 断开的硬取消。
 */
@RestController
@RequestMapping("/api/agent")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat/stream")
    public SseEmitter chat(@RequestBody ChatRequest req) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return chatService.stream(req);
    }

    /** 中途插话：运行中给 Agent 指路，下一个步边界生效。 */
    @PostMapping("/interject")
    public Map<String, Object> interject(@RequestBody InterjectRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isBlank()
                || req.text() == null || req.text().isBlank()) {
            throw new IllegalArgumentException("sessionId 和 text 不能为空");
        }
        boolean queued = chatService.interject(req.sessionId(), req.text());
        if (!queued) {
            return Map.of("ok", false, "message", "该会话当前没有运行中的任务");
        }
        return Map.of("ok", true);
    }

    /** 中途插话请求体。 */
    public record InterjectRequest(String sessionId, String text) {}
}
