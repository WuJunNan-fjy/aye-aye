package com.aye.server.deploy;

import com.aye.server.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeployRunStoreTest {

    private final List<Database> dbs = new ArrayList<>();

    private DeployRunStore store() {
        Database db = new Database("target/deploy-store-test-" + dbs.size());
        dbs.add(db);
        return new DeployRunStore(db);
    }

    @AfterEach
    void closeDbs() {
        dbs.forEach(Database::close);
    }

    @Test
    void createInitializesAllStagesPending() {
        DeployRunStore.DeployRun run = store().createOrGet(1, "sess-a", 10);
        assertEquals(DeployStage.SELECT.id(), run.currentStage());
        assertEquals(7, run.stages().size());
        run.stages().values().forEach(s -> assertEquals("pending", s.status()));
    }

    @Test
    void createOrGetIsIdempotent() {
        DeployRunStore store = store();
        DeployRunStore.DeployRun a = store.createOrGet(1, "sess-b", 10);
        DeployRunStore.DeployRun b = store.createOrGet(1, "sess-b", 10);
        assertEquals(a.id(), b.id());
    }

    @Test
    void updateStagePersists() {
        DeployRunStore store = store();
        DeployRunStore.DeployRun run = store.createOrGet(1, "sess-c", 10);
        store.updateStage(run.id(), DeployStage.BUILD,
                StageState.of("running", "正在打包"));
        DeployRunStore.DeployRun updated = store.get(run.id());
        assertEquals(DeployStage.BUILD.id(), updated.currentStage());
        assertEquals("running", updated.stages().get("build").status());
        assertEquals("正在打包", updated.stages().get("build").summary());
        // 其它阶段保持 pending
        assertEquals("pending", updated.stages().get("select").status());
    }

    @Test
    void retryStageResetsState() {
        DeployRunStore store = store();
        DeployRunStore.DeployRun run = store.createOrGet(1, "sess-d", 10);
        store.updateStage(run.id(), DeployStage.BUILD,
                StageState.of("failed", "构建失败"));
        store.updateStage(run.id(), DeployStage.BUILD,
                StageState.of("running", "重新构建"));
        assertEquals("running", store.get(run.id()).stages().get("build").status());
    }

    @Test
    void planArtifactAndPid() {
        DeployRunStore store = store();
        DeployRunStore.DeployRun run = store.createOrGet(1, "sess-e", 10);
        store.setPlan(run.id(), "Java Maven 项目");
        store.setArtifact(run.id(), "E:/proj/target/app.jar", 38_000_000L);
        store.setRemoteAppDir(run.id(), "/opt/aye-apps/demo");
        store.setRemotePid(run.id(), 4321L);

        DeployRunStore.DeployRun r = store.get(run.id());
        assertEquals("Java Maven 项目", r.plan());
        assertEquals("E:/proj/target/app.jar", r.artifact().localPath());
        assertEquals(38_000_000L, r.artifact().size());
        assertEquals("/opt/aye-apps/demo", r.remoteAppDir());
        assertEquals(4321L, r.remotePid());
    }

    @Test
    void latestBySessionIsolated() {
        DeployRunStore store = store();
        store.createOrGet(1, "sess-x", 10);
        store.createOrGet(2, "sess-y", 20);
        assertEquals(10, store.latestBySession("sess-x").hostId());
        assertEquals(20, store.latestBySession("sess-y").hostId());
        assertNull(store.latestBySession("sess-z"));
    }
}
