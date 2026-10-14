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
 * <p>仅匹配同一会话、执行模式、工作区与工具完整参数。模式或工作区发生变化时
 * 清除旧授权，即使随后切回原模式/工作区也必须重新批准。
 * 仅存内存，同一会话可跨回合复用；删除会话时清空，重启后失效。
 */
@Component
public class SessionCommandGrants {

    private record Scope(String mode, String workspace) {}
    private record Grants(Scope scope, Set<String> operations) {}
    private final Map<String, Grants> grantsBySession = new ConcurrentHashMap<>();

    /** 每轮及每次审批前核对授权范围，变化即撤销旧授权。 */
    public void prepare(String sessionId, String mode, String workspace) {
        Scope scope = new Scope(mode, workspace);
        grantsBySession.compute(sessionId, (id, previous) ->
                previous != null && previous.scope().equals(scope) ? previous
                        : new Grants(scope, ConcurrentHashMap.newKeySet()));
    }

    /** 命中本会话此前 remember 批准过的相同操作。 */
    public boolean isGranted(String sessionId, String mode, String workspace, String toolName, JsonNode input) {
        Grants grants = grantsBySession.get(sessionId);
        return grants != null && grants.scope().equals(new Scope(mode, workspace))
                && grants.operations().contains(key(toolName, input));
    }

    /** 记录一条会话内授权。 */
    public void grant(String sessionId, String mode, String workspace, String toolName, JsonNode input) {
        Grants grants = grantsBySession.get(sessionId);
        if (grants != null && grants.scope().equals(new Scope(mode, workspace))) {
            grants.operations().add(key(toolName, input));
        }
    }

    /** 删除会话时清理。 */
    public void clear(String sessionId) {
        grantsBySession.remove(sessionId);
    }

    private static String key(String toolName, JsonNode input) {
        return toolName + "|" + sha256(input.toString());
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
