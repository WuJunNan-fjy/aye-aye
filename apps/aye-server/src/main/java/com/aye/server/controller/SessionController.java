package com.aye.server.controller;

import com.aye.ai.message.UIMessage;
import com.aye.server.session.SessionEventManager;
import com.aye.server.storage.MessageCodec;
import com.aye.server.storage.SessionStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话 + 历史消息接口。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionStore store;
    private final SessionEventManager eventManager;

    public SessionController(SessionStore store, SessionEventManager eventManager) {
        this.store = store;
        this.eventManager = eventManager;
    }

    @GetMapping
    public Map<String, Object> list() {
        // 附加可空 runningTurn（session_turns 投影 status='running' 的最大 turn_no，单条聚合）。
        // 附加字段 v1 客户端自动忽略；前端再并上本标签页 runtime 的实时状态（D6）。
        List<Map<String, Object>> sessions = new ArrayList<>(store.listSessions());
        List<String> ids = sessions.stream()
                .map(s -> String.valueOf(s.get("id")))
                .toList();
        Map<String, Integer> running = eventManager.runningTurns(ids);
        for (Map<String, Object> s : sessions) {
            s.put("runningTurn", running.get(String.valueOf(s.get("id"))));
        }
        return Map.of("sessions", sessions);
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        List<UIMessage> msgs = store.getMessages(id);
        return Map.of("messages", MessageCodec.messagesToArray(msgs));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        // 先删事件（运行中会话拒绝），再删 sessions/messages 投影
        eventManager.deleteSessionEvents(id);
        store.deleteSession(id);
        return Map.of("success", true);
    }
}
