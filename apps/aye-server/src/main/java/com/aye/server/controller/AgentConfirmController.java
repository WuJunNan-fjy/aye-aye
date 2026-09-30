package com.aye.server.controller;

import com.aye.server.chat.PendingInteractionHub;
import com.aye.server.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 交互返回通道（与 SSE 单向流配合的「人工确认 / 主动询问」回传端点）。
 *
 * <p>SSE 是服务器单向推送给前端的流；当 Agent 需要用户批准或以 ask_user 提问时，
 * 前端渲染「待确认卡片 / 询问卡片」，用户操作后通过这两个 REST 端点把结果回传，
 * 后端 {@link PendingInteractionHub} 将结果 complete 到被阻塞的 Agent 线程，使其继续。
 *
 * <p>安全：confirmId/askId 是随机 UUID 且回填一次性生效，本身无法被「重放」；
 * 这里再加一个每 IP 固定窗口限流，遏制局域网内被滥刷/探测。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentConfirmController {

    /** 限流：单个 IP 在窗口内最多允许的请求次数（人工操作，窗口足够宽）。 */
    private static final int RATE_LIMIT = 30;
    private static final long RATE_WINDOW_MS = 10_000;

    private final PendingInteractionHub hub;
    /** ip → [窗口起始时间戳, 窗口内计数] */
    private final ConcurrentHashMap<String, long[]> rateBuckets = new ConcurrentHashMap<>();

    public AgentConfirmController(PendingInteractionHub hub) {
        this.hub = hub;
    }

    /** 人工审批回传：前端批准/拒绝一次工具执行请求。 */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmRequest req, HttpServletRequest http) {
        requireNotRateLimited(http);
        if (req == null || req.confirmId() == null || req.confirmId().isBlank()) {
            throw new IllegalArgumentException("confirmId 不能为空");
        }
        boolean approved = Boolean.TRUE.equals(req.approved());
        boolean resolved = hub.resolveConfirm(req.confirmId(), approved,
                approved && Boolean.TRUE.equals(req.remember()));
        if (!resolved) {
            return Map.of("ok", false, "message", "确认请求不存在或已过期");
        }
        return Map.of("ok", true, "approved", approved);
    }

    /** ask_user 主动询问回传：前端作答 Agent 的问题。 */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody AskRequest req, HttpServletRequest http) {
        requireNotRateLimited(http);
        if (req == null || req.askId() == null || req.askId().isBlank()) {
            throw new IllegalArgumentException("askId 不能为空");
        }
        boolean resolved = hub.resolveAsk(req.askId(), req.answer());
        if (!resolved) {
            return Map.of("ok", false, "message", "询问请求不存在或已过期");
        }
        return Map.of("ok", true);
    }

    /** 固定窗口限流：IP 在窗口内超过阈值则抛 429。 */
    private void requireNotRateLimited(HttpServletRequest req) {
        if (req == null) return;
        String ip = clientIp(req);
        if (ip == null || ip.isBlank()) return;
        long now = System.currentTimeMillis();
        long[] bucket = rateBuckets.compute(ip, (k, v) -> {
            if (v == null || now - v[0] >= RATE_WINDOW_MS) {
                return new long[]{now, 1L};
            }
            v[1] += 1;
            return v;
        });
        if (bucket[1] > RATE_LIMIT) {
            throw new ApiException(429, "操作过于频繁，请稍后再试", "RATE_LIMITED");
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /**
     * 人工审批请求体。
     * remember=true 且 approved=true：本会话内完全相同的命令/参数后续免确认（见 SessionCommandGrants）。
     */
    public record ConfirmRequest(String confirmId, Boolean approved, Boolean remember) {}

    /** 主动询问请求体。 */
    public record AskRequest(String askId, String answer) {}
}