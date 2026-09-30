package com.aye.server.deploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aye.server.storage.Database;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署账本（deploy_runs）：一次部署的阶段、计划、产物、远程目录与 pid 的权威存储。
 *
 * <p>第一刀一个会话维护一条部署记录（createOrGet 幂等）。阶段结构为
 * {@code Map<stageId, StageState>}，以 JSON 落 stages_json。
 */
public final class DeployRunStore {

    /** 产物记录。 */
    public record Artifact(String localPath, long size) {}

    /** 部署记录视图。 */
    public static final class DeployRun {
        private final long id;
        private final long workspaceId;
        private final String sessionId;
        private final long hostId;
        private final String currentStage;
        private final Map<String, StageState> stages;
        private final String plan;
        private final Artifact artifact;
        private final String remoteAppDir;
        private final Long remotePid;

        DeployRun(long id, long workspaceId, String sessionId, long hostId, String currentStage,
                  Map<String, StageState> stages, String plan, Artifact artifact,
                  String remoteAppDir, Long remotePid) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.currentStage = currentStage;
            this.stages = stages;
            this.plan = plan;
            this.artifact = artifact;
            this.remoteAppDir = remoteAppDir;
            this.remotePid = remotePid;
        }

        public long id() { return id; }
        public long workspaceId() { return workspaceId; }
        public String sessionId() { return sessionId; }
        public long hostId() { return hostId; }
        public String currentStage() { return currentStage; }
        public Map<String, StageState> stages() { return stages; }
        public String plan() { return plan; }
        public Artifact artifact() { return artifact; }
        public String remoteAppDir() { return remoteAppDir; }
        public Long remotePid() { return remotePid; }
    }

    private static final TypeReference<LinkedHashMap<String, StageState>> STAGES_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeployRunStore(Database db) {
        this.db = db;
    }

    /**
     * 取该会话的部署记录，没有则新建（7 阶段全部 pending）。
     *
     * <p>同一会话已有记录但其工作区/主机与本次不同（用户换了项目或换了目标主机）时，
     * 不复用旧记录，而是新开一条部署，避免阶段/产物/pid 串台。
     */
    public DeployRun createOrGet(long workspaceId, String sessionId, long hostId) {
        DeployRun existing = latestBySession(sessionId);
        if (existing != null && existing.workspaceId() == workspaceId
                && (existing.hostId() == hostId || existing.hostId() == 0L)) {
            // hostId=0 是选主机前的占位记录，上传阶段由 setHostId 补填
            return existing;
        }
        return insert(workspaceId, sessionId, hostId);
    }

    private DeployRun insert(long workspaceId, String sessionId, long hostId) {
        Map<String, StageState> stages = new LinkedHashMap<>();
        for (DeployStage s : DeployStage.values()) {
            stages.put(s.id(), new StageState("pending", null, null, null,
                    System.currentTimeMillis()));
        }
        long now = System.currentTimeMillis();
        db.update("""
                        INSERT INTO deploy_runs
                            (workspace_id, session_id, host_id, current_stage, stages_json,
                             plan_json, artifact_json, remote_app_dir, remote_pid,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?)""",
                workspaceId, sessionId, hostId, DeployStage.SELECT.id(),
                writeJson(stages), now, now);
        return get(db.lastInsertRowId());
    }

    /** 更新某阶段状态，并把它设为当前阶段。 */
    public DeployRun updateStage(long runId, DeployStage stage, StageState state) {
        DeployRun run = get(runId);
        if (run == null) throw new IllegalArgumentException("部署记录不存在：" + runId);
        Map<String, StageState> stages = new LinkedHashMap<>(run.stages());
        stages.put(stage.id(), state);
        db.update("UPDATE deploy_runs SET current_stage = ?, stages_json = ?, updated_at = ? WHERE id = ?",
                stage.id(), writeJson(stages), System.currentTimeMillis(), runId);
        return get(runId);
    }

    public DeployRun setPlan(long runId, String plan) {
        db.update("UPDATE deploy_runs SET plan_json = ?, updated_at = ? WHERE id = ?",
                plan, System.currentTimeMillis(), runId);
        return get(runId);
    }

    public DeployRun setArtifact(long runId, String localPath, long size) {
        db.update("UPDATE deploy_runs SET artifact_json = ?, updated_at = ? WHERE id = ?",
                writeJson(new Artifact(localPath, size)), System.currentTimeMillis(), runId);
        return get(runId);
    }

    /** 记录实际上传目标主机（deploy_stage 在选主机前调用，先以 0 占位）。 */
    public DeployRun setHostId(long runId, long hostId) {
        db.update("UPDATE deploy_runs SET host_id = ?, updated_at = ? WHERE id = ?",
                hostId, System.currentTimeMillis(), runId);
        return get(runId);
    }

    public DeployRun setRemoteAppDir(long runId, String remoteAppDir) {
        db.update("UPDATE deploy_runs SET remote_app_dir = ?, updated_at = ? WHERE id = ?",
                remoteAppDir, System.currentTimeMillis(), runId);
        return get(runId);
    }

    public DeployRun setRemotePid(long runId, Long pid) {
        db.update("UPDATE deploy_runs SET remote_pid = ?, updated_at = ? WHERE id = ?",
                pid, System.currentTimeMillis(), runId);
        return get(runId);
    }

    public DeployRun get(long id) {
        List<Map<String, Object>> rows =
                db.query("SELECT * FROM deploy_runs WHERE id = ?", id);
        return rows.isEmpty() ? null : map(rows.get(0));
    }

    /** 会话最新一条部署记录。 */
    public DeployRun latestBySession(String sessionId) {
        if (sessionId == null) return null;
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM deploy_runs WHERE session_id = ? ORDER BY id DESC LIMIT 1",
                sessionId);
        return rows.isEmpty() ? null : map(rows.get(0));
    }

    private DeployRun map(Map<String, Object> row) {
        Map<String, StageState> stages = readJson(String.valueOf(row.get("stages_json")),
                STAGES_TYPE, new LinkedHashMap<>());
        Artifact artifact = null;
        if (row.get("artifact_json") != null) {
            Map<String, Object> a = readJson(String.valueOf(row.get("artifact_json")),
                    MAP_TYPE, new LinkedHashMap<>());
            artifact = new Artifact(String.valueOf(a.get("localPath")),
                    a.get("size") instanceof Number n ? n.longValue() : 0L);
        }
        Long pid = row.get("remote_pid") instanceof Number n ? n.longValue() : null;
        return new DeployRun(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("workspace_id")).longValue(),
                String.valueOf(row.get("session_id")),
                ((Number) row.get("host_id")).longValue(),
                String.valueOf(row.get("current_stage")),
                stages,
                row.get("plan_json") == null ? null : String.valueOf(row.get("plan_json")),
                artifact,
                row.get("remote_app_dir") == null ? null : String.valueOf(row.get("remote_app_dir")),
                pid);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }
}
