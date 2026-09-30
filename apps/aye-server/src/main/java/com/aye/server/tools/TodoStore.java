package com.aye.server.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级待办存储（对标 opencode TodoWriteTool 的会话内状态）。
 *
 * <p>按 {@code sessionId} 隔离，内存态（重启即清空，符合"进度跟踪是会话内辅助"的定位）；
 * 审计留痕仍走 AuditLedger，不受影响。
 */
public final class TodoStore {

    public record Todo(String id, String content, String status) {}

    private final Map<String, List<Todo>> bySession = new ConcurrentHashMap<>();

    /** 整体替换某会话的待办列表（opencode 风格：每次重发完整列表，简单可靠） */
    public synchronized List<Todo> replace(String sessionId, List<Todo> todos) {
        List<Todo> next = new ArrayList<>(todos);
        bySession.put(sessionId, next);
        return List.copyOf(next);
    }

    public List<Todo> list(String sessionId) {
        return List.copyOf(bySession.getOrDefault(sessionId, List.of()));
    }

    public synchronized void clear(String sessionId) {
        bySession.remove(sessionId);
    }

    /** 生成稳定 id（LLM 不负责生成，避免重复/混乱） */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
