package com.aye.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级命令授权（「本会话内相同操作不再询问」）。
 *
 * <p>用户在确认卡勾选 remember 后，把本次 (toolName + 完全相同的命令/参数) 记入该会话；
 * 之后同一会话内完全一致的操作直接放行，不再弹卡。这是<b>会话内、精确匹配</b>的便利机制，
 * 不做模式匹配/前缀匹配（参数差一个路径都视为不同操作，fail-safe）：
 * <ul>
 *   <li>命令类工具（run_ssh / run_ssh_async 等含 command 字段）：
 *       key 绑定 host_key + trim 后的完整命令文本；</li>
 *   <li>其他工具：key 绑定 host_key + 输入 JSON 的 SHA-256（字段不同即不命中）。</li>
 * </ul>
 * 仅存内存，会话结束（SSE emitter 关闭）即清空，绝不跨会话持久化。
 */
@Component
public class SessionCommandGrants {

    private final Map<String, Set<String>> grantsBySession = new ConcurrentHashMap<>();

    /** 命中本会话此前 remember 批准过的相同操作。 */
    public boolean isGranted(String sessionId, String toolName, JsonNode input) {
        Set<String> grants = grantsBySession.get(sessionId);
        return grants != null && grants.contains(key(toolName, input));
    }

    /** 记录一条会话内授权。 */
    public void grant(String sessionId, String toolName, JsonNode input) {
        grantsBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(key(toolName, input));
    }

    /** 会话结束清理（emitter 关闭时调用）。 */
    public void clear(String sessionId) {
        grantsBySession.remove(sessionId);
    }

    private static String key(String toolName, JsonNode input) {
        String host = input.path("host_key").asText("");
        String command = input.path("command").asText("");
        if (!command.isBlank()) {
            return toolName + "|cmd|" + host + "|" + command.trim();
        }
        return toolName + "|json|" + host + "|" + sha256(input.toString());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 是 JDK 内置算法，理论不会缺失；兜底用原始文本（功能退化为等值匹配，不影响安全）
            return s;
        }
    }
}
