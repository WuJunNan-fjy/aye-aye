package com.aye.server.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测中心只读接口（事件日志投影）。
 *
 * <ul>
 *   <li>{@code GET /api/observability/activity}                操作日志：工具操作行（事件聚合）</li>
 *   <li>{@code GET /api/observability/turns}                   Agent 追踪：turn 分页列表（状态/token/步数）</li>
 *   <li>{@code GET /api/observability/sessions/{id}/trajectory} 单会话轨迹时间线</li>
 * </ul>
 * 全部读自 {@code session_events}/{@code session_turns}，与对话页共用同一事实源，
 * 因此刷新页面 / 后端重启后历史与状态完全一致，并可按 sessionId 与对话双向跳转。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilityProjection projection;

    public ObservabilityController(ObservabilityProjection projection) {
        this.projection = projection;
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(
            @RequestParam(value = "limit", required = false) Long limit,
            @RequestParam(value = "offset", required = false) Long offset,
            @RequestParam(value = "hostId", required = false) Long hostId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "approvalStatus", required = false) String approvalStatus,
            @RequestParam(value = "startMs", required = false) Long startMs,
            @RequestParam(value = "endMs", required = false) Long endMs,
            @RequestParam(value = "search", required = false) String search) {

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("limit", limit == null ? 100 : Math.max(1, Math.min(200, limit)));
        opts.put("offset", offset == null ? 0 : Math.max(0, offset));
        if (hostId != null) opts.put("hostId", hostId);
        if (sessionId != null && !sessionId.isBlank()) opts.put("sessionId", sessionId);
        if (toolName != null && !toolName.isBlank()) opts.put("toolName", toolName);
        if (status != null && !status.isBlank()) opts.put("status", status);
        if (approvalStatus != null && !approvalStatus.isBlank()) opts.put("approvalStatus", approvalStatus);
        if (startMs != null) opts.put("startMs", startMs);
        if (endMs != null) opts.put("endMs", endMs);
        if (search != null && !search.isBlank()) opts.put("search", search);

        ObservabilityProjection.ActivityResult r = projection.activity(opts);
        return Map.of("rows", r.rows(), "total", r.total(),
                "stats", r.stats(), "tools", r.tools());
    }

    @GetMapping("/turns")
    public Map<String, Object> turns(
            @RequestParam(value = "limit", required = false) Long limit,
            @RequestParam(value = "offset", required = false) Long offset,
            @RequestParam(value = "hostId", required = false) Long hostId,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "search", required = false) String search) {

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("limit", limit == null ? 100 : Math.max(1, Math.min(200, limit)));
        opts.put("offset", offset == null ? 0 : Math.max(0, offset));
        if (hostId != null) opts.put("hostId", hostId);
        if (state != null && !state.isBlank()) opts.put("state", state);
        if (mode != null && !mode.isBlank()) opts.put("mode", mode);
        if (search != null && !search.isBlank()) opts.put("search", search);

        ObservabilityProjection.TurnResult r = projection.turns(opts);
        return Map.of("turns", r.turns(), "total", r.total());
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(
            @RequestParam(value = "limit", required = false) Long limit,
            @RequestParam(value = "offset", required = false) Long offset,
            @RequestParam(value = "hostId", required = false) Long hostId,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "search", required = false) String search) {

        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("limit", limit == null ? 100 : Math.max(1, Math.min(200, limit)));
        opts.put("offset", offset == null ? 0 : Math.max(0, offset));
        if (hostId != null) opts.put("hostId", hostId);
        if (state != null && !state.isBlank()) opts.put("state", state);
        if (mode != null && !mode.isBlank()) opts.put("mode", mode);
        if (search != null && !search.isBlank()) opts.put("search", search);

        ObservabilityProjection.SessionResult r = projection.sessions(opts);
        return Map.of("sessions", r.sessions(), "total", r.total());
    }

    @GetMapping("/sessions/{sessionId}/trajectory")
    public Map<String, Object> trajectory(@PathVariable String sessionId) {
        return projection.trajectory(sessionId);
    }
}
