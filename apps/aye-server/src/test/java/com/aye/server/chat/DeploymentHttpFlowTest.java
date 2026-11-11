package com.aye.server.chat;

import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.*;
import com.aye.ai.util.Json;
import com.aye.server.AyeApplication;
import com.aye.server.session.SessionEventManager;
import com.aye.server.storage.Database;
import com.aye.server.storage.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/controllers/AgentLoop/SQLite with scripted local LLM and inert deployment tools.
 * No SSH connection or shell command is performed. These tests do not certify remote deployment. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeploymentHttpFlowTest {
    @TempDir static Path tmp;
    private ConfigurableApplicationContext app;
    private HttpServer model;
    private String base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Queue<JsonNode> script = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean modelFails = new AtomicBoolean();
    private final AtomicBoolean toolFails = new AtomicBoolean();
    private final AtomicInteger executions = new AtomicInteger();
    private String session;
    private long workspace;
    private long host;

    @BeforeAll void boot() throws Exception {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (modelFails.get()) {
                byte[] error = "{\"error\":{\"message\":\"local test unavailable\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, error.length);
                try (var out = exchange.getResponseBody()) { out.write(error); }
                return;
            }
            JsonNode next = script.poll();
            var delta = Json.object();
            if (next != null) {
                var fn = Json.object().put("name", next.path("name").asText()).put("arguments", next.path("args").toString());
                var call = Json.object().put("index", 0).put("id", UUID.randomUUID().toString()).put("type", "function");
                call.set("function", fn);
                delta.putArray("tool_calls").add(call);
            } else delta.put("content", "Local simulation finished; no real server was changed.");
            var choice = Json.object().put("index", 0).put("finish_reason", next == null ? "stop" : "tool_calls");
            choice.set("delta", delta);
            var chunk = Json.object();
            chunk.putArray("choices").add(choice);
            byte[] body = ("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        model.start();
        app = new SpringApplicationBuilder(AyeApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--server.shutdown=immediate", "--spring.main.banner-mode=off",
                "--aye.data-dir=" + tmp.resolve("data"), "--aye.cwd=" + tmp,
                "--aye.default-api-key=local-fixture-only", "--aye.max-steps=40",
                "--aye.default-base-url=http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        ToolRegistry registry = app.getBean(ToolRegistry.class);
        // Keep the real stage ledger tool, replace all executable tools in this isolated application.
        for (Tool tool : registry.list()) if (!tool.name().equals("deploy_stage")) registry.unregister(tool.name());
        for (String name : List.of("run_local_async", "deploy_upload", "deploy_start_remote")) {
            registry.register(new Tool() {
                public String name() { return name; }
                public String description() { return "Inert deployment simulation"; }
                public ToolCategory category() { return name.equals("deploy_upload") ? ToolCategory.WRITE : ToolCategory.EXECUTE; }
                public JsonNode inputSchema() { return JsonSchema.object().property("command", JsonSchema.string()).build(); }
                public String resolveCommandText(JsonNode input) { return input.path("command").asText(); }
                public Object execute(JsonNode input, ToolContext context) {
                    executions.incrementAndGet();
                    if (toolFails.get()) throw new IllegalStateException("Simulated build failure");
                    return "Simulated operation only";
                }
            });
        }
    }

    @BeforeEach void prepare() throws Exception {
        script.clear(); modelFails.set(false); toolFails.set(false); executions.set(0);
        session = "http-flow-" + UUID.randomUUID();
        Path project = Files.createDirectories(tmp.resolve(session));
        Files.writeString(project.resolve("README.md"), "Local test fixture");
        workspace = request("POST", "/api/workspaces", Json.object().put("path", project.toString()).put("title", "Fixture project"), 200).path("id").asLong();
        host = request("POST", "/api/hosts", Json.object().put("name", "Inert target").put("address", "127.0.0.1").put("username", "fixture"), 200).path("id").asLong();
    }

    @AfterEach void finish() throws Exception {
        app.getBean(ChatService.class).cancel(session);
        waitUntil(() -> !app.getBean(SessionEventManager.class).isActive(session));
    }
    @AfterAll void close() {
        if (app != null) app.close();
        if (model != null) model.stop(0);
    }

    @Test void deploymentApprovalStagesHistoryAndReloadUseRealHttp() throws Exception {
        stage("select", "done"); stage("detect", "done"); stage("prepare", "done");
        call("run_local_async"); stage("build", "done"); stage("confirm", "done");
        call("deploy_upload"); stage("upload", "done"); call("deploy_start_remote"); stage("verify", "done");
        start();
        Set<String> approved = new HashSet<>();
        waitUntil(() -> {
            for (JsonNode event : tail()) {
                if (event.path("type").asText().equals("interaction/requested")) {
                    String id = event.path("data").path("interactionId").asText();
                    if (!approved.contains(id)) {
                        assertEquals(approved.size(), executions.get(), "No mutation before approval");
                        if (confirm(id, true)) approved.add(id);
                    }
                }
            }
            return ended(1);
        });
        assertEquals(3, approved.size()); assertEquals(3, executions.get());
        JsonNode run = request("GET", "/api/deploy/runs/latest?sessionId=" + session, null, 200).path("run");
        assertEquals(workspace, run.path("workspaceId").asLong());
        run.path("stages").forEach(s -> assertEquals("done", s.path("status").asText()));
        assertEquals(7, run.path("stages").size());
        JsonNode records = request("GET", "/api/sessions", null, 200).path("sessions");
        boolean found = false;
        for (JsonNode row : records) if (session.equals(row.path("id").asText())) {
            found = true; assertTrue(row.path("deploy").asBoolean()); assertEquals(workspace, row.path("workspaceId").asLong());
        }
        assertTrue(found);
        assertFalse(request("GET", "/api/sessions/" + session + "/messages", null, 200).path("messages").isEmpty());
        long lastSeq = tail().get(tail().size() - 1).path("seq").asLong();
        assertTrue(request("GET", "/api/sessions/" + session + "/events/tail?after=" + lastSeq, null, 200).path("events").isEmpty());
        var streamRequest = HttpRequest.newBuilder(URI.create(base + "/api/sessions/" + session + "/events?after=-1"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var stream = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            List<JsonNode> envelopes = new ArrayList<>();
            while (envelopes.size() < 2) {
                String line = reader.readLine();
                assertNotNull(line);
                if (line.startsWith("data:")) envelopes.add(Json.parse(line.substring(5)));
            }
            assertEquals("hello", envelopes.get(0).path("type").asText());
            assertEquals("snapshot", envelopes.get(1).path("type").asText());
            assertFalse(envelopes.get(1).path("events").isEmpty());
        }
        // A fresh store/connection sees persisted context, as after a process restart.
        Database reopened = new Database(tmp.resolve("data").toString());
        try { assertEquals(host, new SessionStore(reopened).deploymentContext(session).hostId()); }
        finally { reopened.close(); }
    }

    @Test void rejectAndReplayNeverExecuteTool() throws Exception {
        call("deploy_upload"); start();
        String id = interaction();
        assertEquals(0, executions.get());
        assertTrue(confirm(id, false));
        waitUntil(() -> ended(1));
        assertEquals(0, executions.get());
        assertFalse(confirm(id, true));
    }

    @Test void stopPendingApprovalThenResumeKeepsTargetAndRejectsDuplicateStart() throws Exception {
        call("deploy_start_remote"); start();
        String id = interaction();
        request("POST", "/api/v2/agent/turns", payload(), 409);
        assertTrue(request("POST", "/api/v2/agent/cancel", Json.object().put("sessionId", session), 200).path("ok").asBoolean());
        waitUntil(() -> ended(1));
        assertFalse(confirm(id, true)); assertEquals(0, executions.get());
        assertFalse(request("POST", "/api/v2/agent/cancel", Json.object().put("sessionId", session), 200).path("ok").asBoolean());
        request("POST", "/api/v2/agent/turns", Json.object().put("sessionId", session).put("message", "continue").put("mode", "write_confirm"), 202);
        waitUntil(() -> ended(2));
        assertEquals(host, app.getBean(SessionStore.class).deploymentContext(session).hostId());
        assertEquals(workspace, request("GET", "/api/workspaces/bound?sessionId=" + session, null, 200).path("workspace").path("id").asLong());
    }

    @Test void failedBuildIsVisibleAndCanBeRetriedWithoutLosingHistory() throws Exception {
        toolFails.set(true); call("run_local_async"); stage("build", "failed"); start();
        assertTrue(confirm(interaction(), true)); waitUntil(() -> ended(1));
        assertEquals("failed", request("GET", "/api/deploy/runs/latest?sessionId=" + session, null, 200).path("run").path("stages").path("build").path("status").asText());
        boolean error = false;
        for (JsonNode event : tail()) if (event.path("type").asText().equals("tool/result") && event.path("data").path("isError").asBoolean()) error = true;
        assertTrue(error);
        toolFails.set(false); call("run_local_async"); stage("build", "done"); start();
        String next = interactionForTurn(2); assertTrue(confirm(next, true)); waitUntil(() -> ended(2));
        assertEquals("done", request("GET", "/api/deploy/runs/latest?sessionId=" + session, null, 200).path("run").path("stages").path("build").path("status").asText());
        assertEquals(2, executions.get());
    }

    @Test void modelOutageProducesErrorAndAllowsRetry() throws Exception {
        modelFails.set(true); start(); waitUntil(() -> ended(1));
        boolean error = false;
        for (JsonNode event : tail()) if (event.path("type").asText().equals("turn/end") && event.path("data").path("reason").asText().equals("error")) error = true;
        assertTrue(error); assertEquals(0, executions.get());
        modelFails.set(false); start(); waitUntil(() -> ended(2));
    }

    private JsonNode payload() { return Json.object().put("sessionId", session).put("message", "local deployment simulation").put("workspaceId", workspace).put("hostId", host).put("deploy", true).put("mode", "write_confirm"); }
    private void start() throws Exception { request("POST", "/api/v2/agent/turns", payload(), 202); }
    private void call(String name) { var item = Json.object().put("name", name); item.set("args", Json.object().put("command", "npm run build")); script.add(item); }
    private void stage(String stage, String status) { var item = Json.object().put("name", "deploy_stage"); item.set("args", Json.object().put("stage", stage).put("status", status).put("summary", "Local simulation")); script.add(item); }
    private boolean confirm(String id, boolean approved) throws Exception { return request("POST", "/api/agent/confirm", Json.object().put("confirmId", id).put("approved", approved).put("remember", false), 200).path("ok").asBoolean(); }
    private JsonNode tail() throws Exception { return request("GET", "/api/sessions/" + session + "/events/tail?after=-1&limit=1000", null, 200).path("events"); }
    private boolean ended(int count) throws Exception {
        int found = 0;
        for (JsonNode e : tail()) if (e.path("type").asText().equals("turn/end")) found++;
        return found >= count && !app.getBean(SessionEventManager.class).isActive(session);
    }
    private String interaction() throws Exception { return interactionForTurn(1); }
    private String interactionForTurn(int turn) throws Exception {
        String[] id = {null};
        waitUntil(() -> { for (JsonNode e : tail()) if (e.path("turnNo").asInt() == turn && e.path("type").asText().equals("interaction/requested")) id[0] = e.path("data").path("interactionId").asText(); return id[0] != null; });
        return id[0];
    }
    private JsonNode request(String method, String path, JsonNode body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), path + ": " + response.body());
        return Json.parse(response.body());
    }
    private interface Check { boolean get() throws Exception; }
    private void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) { if (check.get()) return; Thread.sleep(30); }
        fail("Timed out: " + session);
    }
}
