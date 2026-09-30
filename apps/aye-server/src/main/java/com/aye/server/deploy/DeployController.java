package com.aye.server.deploy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署账本查询：/api/deploy/runs。
 *
 * <p>前端阶段卡片在 turn 运行时按 2s 轮询 latest（事件日志恢复后同样可用）。
 */
@RestController
@RequestMapping("/api/deploy/runs")
public class DeployController {

    private final DeployRunStore runs;

    public DeployController(DeployRunStore runs) {
        this.runs = runs;
    }

    @GetMapping("/latest")
    public Map<String, Object> latest(@RequestParam String sessionId) {
        DeployRunStore.DeployRun run = runs.latestBySession(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run == null ? null : toMap(run));
        return out;
    }

    /** 投影为 JSON 友好的 Map。 */
    static Map<String, Object> toMap(DeployRunStore.DeployRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("workspaceId", r.workspaceId());
        m.put("sessionId", r.sessionId());
        m.put("hostId", r.hostId());
        m.put("currentStage", r.currentStage());
        m.put("stages", r.stages());
        m.put("plan", r.plan());
        if (r.artifact() != null) {
            m.put("artifact", Map.of(
                    "localPath", r.artifact().localPath(),
                    "size", r.artifact().size()));
        }
        m.put("remoteAppDir", r.remoteAppDir());
        m.put("remotePid", r.remotePid());
        return m;
    }
}
