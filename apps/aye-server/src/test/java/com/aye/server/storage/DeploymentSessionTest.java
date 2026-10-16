package com.aye.server.storage;

import com.aye.server.deploy.WorkspaceStore;
import com.aye.server.controller.ModelController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentSessionTest {
    @TempDir Path tmp;
    private record DatabaseScope(Database db) implements AutoCloseable {
        public void close() { db.close(); }
    }

    @Test
    void projectAssociationAndDeploymentModeSurviveRestartAndProjectRemoval() throws Exception {
        String data = tmp.resolve("data").toString();
        long workspaceId;
        try (var scope = new DatabaseScope(new Database(data))) {
            Database db = scope.db();
            var sessions = new SessionStore(db);
            var workspaces = new WorkspaceStore(db);
            workspaceId = workspaces.create(Files.createDirectories(tmp.resolve("project")).toString(), "demo", "local", null).id();
            sessions.createSession("deploy", "上线 demo", 12L);
            sessions.createSession("legacy", "旧会话", null);
            workspaces.bindSession("deploy", workspaceId);
            sessions.markDeployment("deploy", 12L);
        }
        try (var scope = new DatabaseScope(new Database(data))) {
            Database db = scope.db();
            var sessions = new SessionStore(db);
            var row = sessions.listSessions().stream().filter(s -> "deploy".equals(s.get("id"))).findFirst().orElseThrow();
            assertEquals(workspaceId, ((Number) row.get("workspaceId")).longValue());
            assertEquals("demo", row.get("workspaceTitle"));
            assertEquals(true, row.get("deploy"));
            assertEquals(12L, sessions.deploymentContext("deploy").hostId());
            new WorkspaceStore(db).delete(workspaceId);
            assertEquals(2, sessions.listSessions().size(), "Deleting a project must not hide history");
            assertTrue(sessions.listSessions().stream().allMatch(s -> s.get("workspaceId") == null));
            assertTrue(sessions.deploymentContext("deploy").deploy());
            assertFalse(sessions.deploymentContext("legacy").deploy());
        }
    }

    @Test
    void oldDatabaseGainsDeploymentColumnWithoutChangingHistory() throws Exception {
        Path data = Files.createDirectories(tmp.resolve("legacy"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("aye.db")); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT, host_id INTEGER, created_at INTEGER, updated_at INTEGER)");
            statement.execute("INSERT INTO sessions VALUES ('old', 'existing', 4, 1, 2)");
        }
        try (var scope = new DatabaseScope(new Database(data.toString()))) {
            Database db = scope.db();
            var row = new SessionStore(db).listSessions().get(0);
            assertEquals("existing", row.get("title"));
            assertEquals(false, row.get("deploy"));
            assertNull(row.get("workspaceId"));
        }
    }

    @Test
    void firstRunModelEndpointReturnsNullAndDisabledDefaultFallsBack() {
        try (var scope = new DatabaseScope(new Database(tmp.resolve("models").toString()))) {
            Database db = scope.db();
            var store = new ModelProviderStore(db);
            var controller = new ModelController(store, new ObjectMapper());
            assertTrue(controller.defaultModel().containsKey("default"));
            assertNull(controller.defaultModel().get("default"));
            store.upsertProvider("disabled", null, "http://localhost", false);
            store.addModel("disabled", "old", "old", null, null, null);
            store.setDefault("disabled", "old");
            store.upsertProvider("enabled", null, "http://localhost", true);
            store.addModel("enabled", "new", "new", null, null, null);
            assertEquals(new ModelProviderStore.DefaultRef("enabled", "new"), store.getDefaultRef());
        }
    }

    @Test
    void deploymentBindingsCannotBeChangedThroughWorkspaceApi() throws Exception {
        try (var scope = new DatabaseScope(new Database(tmp.resolve("bindings").toString()))) {
            var projects = new WorkspaceStore(scope.db());
            var sessions = new SessionStore(scope.db());
            long first = projects.create(Files.createDirectories(tmp.resolve("first")).toString(), null, "local", null).id();
            long second = projects.create(Files.createDirectories(tmp.resolve("second")).toString(), null, "local", null).id();
            sessions.createSession("deployment", "deployment", 1L);
            projects.bindSession("deployment", first);
            sessions.markDeployment("deployment", 1L);
            var controller = new com.aye.server.controller.WorkspaceController(projects, null, null, null, sessions);
            assertDoesNotThrow(() -> controller.bind(java.util.Map.of("sessionId", "deployment", "workspaceId", first)));
            assertThrows(IllegalArgumentException.class, () -> controller.bind(java.util.Map.of("sessionId", "deployment", "workspaceId", second)));
            assertThrows(IllegalArgumentException.class, () -> controller.unbind(java.util.Map.of("sessionId", "deployment")));
            assertEquals(first, projects.boundWorkspace("deployment").id());
        }
    }

    @Test
    void upgradeRecognizesOldDeploymentButNotOrdinaryProjectChat() throws Exception {
        String data = tmp.resolve("upgrade").toString();
        try (var scope = new DatabaseScope(new Database(data))) {
            Database db = scope.db();
            var sessions = new SessionStore(db);
            var projects = new WorkspaceStore(db);
            long id = projects.create(Files.createDirectories(tmp.resolve("old-project")).toString(), null, "local", null).id();
            sessions.createSession("deployed", "old deployment", 1L);
            sessions.createSession("chat", "project question", 1L);
            projects.bindSession("deployed", id);
            projects.bindSession("chat", id);
            db.update("INSERT INTO deploy_runs (workspace_id, session_id, host_id, current_stage, stages_json, created_at, updated_at) VALUES (?, 'deployed', 1, 'verify', '{}', 1, 2)", id);
        }
        try (var scope = new DatabaseScope(new Database(data))) {
            var sessions = new SessionStore(scope.db());
            assertTrue(sessions.deploymentContext("deployed").deploy());
            assertFalse(sessions.deploymentContext("chat").deploy());
        }
    }
}
