package com.aye.server.chat;

import com.aye.ai.permission.ToolCategory;
import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventType;
import com.aye.ai.tool.*;
import com.aye.ai.util.Json;
import com.aye.server.config.AppProperties;
import com.aye.server.config.LocalPathAuthority;
import com.aye.server.deploy.LocalCommandClassifier;
import com.aye.server.deploy.WorkspaceStore;
import com.aye.server.local.LocalAttachmentRegistry;
import com.aye.server.session.SessionEventManager;
import com.aye.server.session.SessionEventStore;
import com.aye.server.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real ChatService/AgentLoop/approval return path, with a local fake LLM and inert tools. */
class ChatPermissionFlowTest {
    @TempDir Path tmp;
    private Database db;
    private SessionEventManager events;
    private HttpServer llm;
    private ChatService service;
    private SettingsStore settings;
    private SessionCommandGrants grants;
    private PendingInteractionHub hub;
    private AppProperties props;
    private ToolRegistry tools;
    private WorkspaceStore workspaces;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger executions = new AtomicInteger();
    private String toolName;
    private String command = "npm run build";
    private static final String SESSION = "permission-test";

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tmp.resolve("data").toString());
        settings = new SettingsStore(db);
        grants = new SessionCommandGrants();
        hub = new PendingInteractionHub();
        events = new SessionEventManager(new SessionEventStore(db));
        props = new AppProperties();
        props.setDataDir(tmp.toString());
        props.setCwd(tmp.toString());
        props.setDefaultApiKey("fake-test-key");
        llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var delta = Json.object();
            boolean toolCall = requests.getAndIncrement() % 2 == 0;
            if (toolCall) {
                var fn = Json.object().put("name", toolName)
                        .put("arguments", Json.object().put("command", command).toString());
                var call = Json.object().put("index", 0).put("id", "call-test").put("type", "function");
                call.set("function", fn);
                delta.putArray("tool_calls").add(call);
            } else {
                delta.put("content", "done");
            }
            var choice = Json.object().put("index", 0).put("finish_reason", toolCall ? "tool_calls" : "stop");
            choice.set("delta", delta);
            var chunk = Json.object();
            chunk.putArray("choices").add(choice);
            byte[] body = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        llm.start();
        props.setDefaultBaseUrl("http://127.0.0.1:" + llm.getAddress().getPort() + "/v1");
        tools = new ToolRegistry();
        workspaces = new WorkspaceStore(db);
        service = new ChatService(new SessionStore(db), new ModelProviderStore(db), props, tools,
                new AuditStore(db), new HostStore(db), hub, grants, events, null,
                new LocalAttachmentRegistry(), workspaces, new LocalCommandClassifier(),
                new LocalPathAuthority(props), settings);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.cancel(SESSION);
            await(() -> !events.isActive(SESSION));
        }
        if (llm != null) llm.stop(0);
        if (events != null) events.shutdown();
        if (db != null) db.close();
    }

    @ParameterizedTest
    @CsvSource({
            "write_confirm,deploy_upload,WRITE", "all_confirm,deploy_upload,WRITE",
            "write_confirm,deploy_extract_remote,EXECUTE", "all_confirm,deploy_extract_remote,EXECUTE",
            "write_confirm,deploy_start_remote,EXECUTE", "all_confirm,deploy_start_remote,EXECUTE",
            "write_confirm,run_local_async,EXECUTE", "all_confirm,run_local_async,EXECUTE",
            "all_confirm,read_local_file,READ"
    })
    void rejectingApprovalPreventsDeploymentBuildAndReadExecution(String mode, String tool, ToolCategory category) throws Exception {
        register(tool, category);
        service.start(request(mode));
        SessionEvent interaction = awaitInteraction();
        assertEquals(0, executions.get(), "Tool must remain suspended before approval");
        String confirmId = interaction.data().path("interactionId").asText();
        await(() -> hub.resolveConfirm(confirmId, false, false));
        awaitFinished();
        assertEquals(0, executions.get(), "Rejecting approval must prevent execution");
    }

    @Test
    void approvingDeploymentRunsExactlyOnce() throws Exception {
        register("deploy_start_remote", ToolCategory.EXECUTE);
        service.start(request("all_confirm"));
        String confirmId = awaitInteraction().data().path("interactionId").asText();
        await(() -> hub.resolveConfirm(confirmId, true, true));
        awaitFinished();
        assertEquals(1, executions.get());
        assertFalse(grants.isGranted(SESSION, "all_confirm", "", toolName,
                Json.object().put("command", command)), "All-confirm cannot remember approval");
    }

    @Test
    void dangerousLocalCommandCannotUseRememberedGrant() throws Exception {
        command = "rm -rf /";
        register("run_local_async", ToolCategory.EXECUTE);
        grants.prepare(SESSION, "write_confirm", "");
        grants.grant(SESSION, "write_confirm", "", toolName, Json.object().put("command", command));
        service.start(request("write_confirm"));
        awaitFinished();
        assertEquals(0, executions.get());
    }

    @Test
    void allConfirmIgnoresPreexistingGrants() throws Exception {
        register("run_local_async", ToolCategory.EXECUTE);
        grants.prepare(SESSION, "all_confirm", "");
        grants.grant(SESSION, "all_confirm", "", toolName, Json.object().put("command", command));
        service.start(request("all_confirm"));
        String confirmId = awaitInteraction().data().path("interactionId").asText();
        await(() -> hub.resolveConfirm(confirmId, false, false));
        awaitFinished();
        assertEquals(0, executions.get());
    }

    @Test
    void highRiskApprovalCannotBeRemembered() throws Exception {
        command = "reboot";
        register("run_ssh", ToolCategory.EXECUTE);
        service.start(request("write_confirm"));
        SessionEvent interaction = awaitInteraction();
        assertFalse(interaction.data().path("tags").isEmpty());
        String confirmId = interaction.data().path("interactionId").asText();
        await(() -> hub.resolveConfirm(confirmId, true, true));
        awaitFinished();
        assertEquals(1, executions.get());
        assertFalse(grants.isGranted(SESSION, "write_confirm", "", toolName,
                Json.object().put("command", command)));
    }

    @Test
    void changingWorkspaceDuringApprovalInvalidatesApproval() throws Exception {
        register("run_local_async", ToolCategory.EXECUTE);
        var a = workspaces.create(Files.createDirectories(tmp.resolve("a")).toString(), null, "local", null);
        var b = workspaces.create(Files.createDirectories(tmp.resolve("b")).toString(), null, "local", null);
        workspaces.bindSession(SESSION, a.id());
        service.start(request("write_confirm"));
        String confirmId = awaitInteraction().data().path("interactionId").asText();
        workspaces.bindSession(SESSION, b.id());
        await(() -> hub.resolveConfirm(confirmId, true, true));
        awaitFinished();
        assertEquals(0, executions.get(), "Approval for one project cannot execute in another project");
    }

    @Test
    void missingModeUsesSavedDefaultDespiteTrustedApplicationConfig() throws Exception {
        register("deploy_start_remote", ToolCategory.EXECUTE);
        settings.setExecutionMode("readonly");
        props.setTrustLevel("trusted");
        service.start(request(null));
        awaitFinished();
        assertEquals(0, executions.get());
        SessionEvent start = events.open(SESSION).events().stream()
                .filter(e -> SessionEventType.TURN_START.equals(e.type())).findFirst().orElseThrow();
        assertEquals("readonly", start.data().path("mode").asText());
    }

    @Test
    void invalidModesFailBeforeStartingBothApiPaths() {
        assertThrows(IllegalArgumentException.class, () -> service.start(request("trusted")));
        assertThrows(IllegalArgumentException.class, () -> service.stream(request("invalid")));
        assertEquals(0, requests.get());
    }

    private ChatRequest request(String mode) {
        return new ChatRequest(SESSION, "test approval", null, null, null, mode, null, null, false);
    }

    private void register(String name, ToolCategory category) {
        toolName = name;
        tools.register(new Tool() {
            public String name() { return name; }
            public ToolCategory category() { return category; }
            public String description() { return "Inert approval test tool"; }
            public JsonNode inputSchema() { return JsonSchema.object().property("command", JsonSchema.string()).build(); }
            public boolean isConcurrencySafe() { return false; }
            public String resolveCommandText(JsonNode input) { return input.path("command").asText(); }
            public Object execute(JsonNode input, ToolContext ctx) { return executions.incrementAndGet(); }
        });
    }

    private SessionEvent awaitInteraction() throws Exception {
        await(() -> events.open(SESSION).events().stream()
                .anyMatch(e -> SessionEventType.INTERACTION_REQUESTED.equals(e.type()))
                || events.open(SESSION).lastTurnEndReason() != null);
        return events.open(SESSION).events().stream()
                .filter(e -> SessionEventType.INTERACTION_REQUESTED.equals(e.type())).findFirst().orElseThrow();
    }

    private void awaitFinished() throws Exception {
        await(() -> events.open(SESSION).lastTurnEndReason() != null && !events.isActive(SESSION));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for approval flow");
    }
}
