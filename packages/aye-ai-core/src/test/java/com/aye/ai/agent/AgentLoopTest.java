package com.aye.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.event.AgentEvent;
import com.aye.ai.event.AgentEvent.DoneEvent;
import com.aye.ai.event.AgentEvent.ErrorEvent;
import com.aye.ai.event.AgentEvent.TextDeltaEvent;
import com.aye.ai.event.AgentEvent.ToolCallEvent;
import com.aye.ai.event.AgentEvent.ToolResultEvent;
import com.aye.ai.llm.LlmTypes.LlmProviderConfig;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart.TextPart;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.tool.ToolRegistry;
import com.aye.ai.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentLoop 关键控制路径单测。
 *
 * <p>LLM 侧用 JDK 内置 {@link HttpServer} 桩出 OpenAI 兼容的 {@code /chat/completions}
 * SSE 端点，按队列逐次返回脚本化响应——零生产代码改动，同时覆盖真实的
 * LlmClient SSE 解析 + ToolCallAccumulator 链路。
 *
 * <p>覆盖路径：纯文本收尾 / 工具往返与 tool 消息配对 / 步边界中止 / HTTP 5xx /
 * 最大步数软收尾（收尾轮不下发工具）/ 死循环熔断 / 中途插话注入 / 未知工具错误回灌后续跑。
 */
class AgentLoopTest {

    private static HttpServer server;
    private static String baseUrl;

    /** 服务端收到的全部请求体（按调用顺序），供断言 wire 协议。 */
    private static final List<String> REQUEST_BODIES = Collections.synchronizedList(new ArrayList<>());
    /** 脚本化响应队列：每次 HTTP 请求消费一条。 */
    private static final Queue<Resp> RESPONSES = new ConcurrentLinkedQueue<>();

    private record Resp(int status, String body) {}

    @BeforeAll
    static void startFakeLlm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", AgentLoopTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopFakeLlm() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        REQUEST_BODIES.clear();
        RESPONSES.clear();
    }

    private static void handle(HttpExchange ex) throws IOException {
        byte[] req = ex.getRequestBody().readAllBytes();
        REQUEST_BODIES.add(new String(req, StandardCharsets.UTF_8));
        Resp r = RESPONSES.poll();
        if (r == null) {
            r = new Resp(500, "{\"error\":{\"message\":\"unexpected extra request\"}}");
        }
        byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.sendResponseHeaders(r.status(), body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ------------------------------------------------------------------
    // 1. 纯文本回答：无工具调用 → DoneEvent 正常收尾
    // ------------------------------------------------------------------
    @Test
    void textOnlyReply_emitsTextAndDone() throws Exception {
        RESPONSES.add(new Resp(200, sseText("你好，我是 Aye")));

        List<AgentEvent> events = runLoop(new ToolRegistry(), new AgentLoopOptions(), "你好");

        assertEquals("你好，我是 Aye", joinedText(events));
        assertLastIsDone(events);
        assertTrue(none(events, ToolCallEvent.class), "纯文本轮不应出现工具调用事件");
        assertEquals(1, REQUEST_BODIES.size());
        assertTrue(REQUEST_BODIES.get(0).contains("\"model\":\"test-model\""));
    }

    // ------------------------------------------------------------------
    // 2. 工具往返：tool_call → 执行 → tool 结果消息配对 → 最终回答
    //    第二次请求必须携带 assistant(tool_calls) + role=tool 配对消息，
    //    否则 DeepSeek 等端点直接 400（历史上的真实事故形态）。
    // ------------------------------------------------------------------
    @Test
    void toolCall_executesAndPairsToolMessageBeforeNextRequest() throws Exception {
        RESPONSES.add(new Resp(200, sseToolCall("call-1", "echo", "{\"q\":\"hi\"}")));
        RESPONSES.add(new Resp(200, sseText("已完成")));
        EchoTool echo = new EchoTool();
        ToolRegistry registry = registryWith(echo);

        List<AgentEvent> events = runLoop(registry, new AgentLoopOptions(), "帮我回显 hi");

        assertLastIsDone(events);
        assertEquals(1, echo.calls.get(), "工具应恰好执行一次");
        assertTrue(any(events, ToolCallEvent.class), "应收到 tool_call 事件");
        ToolResultEvent result = first(events, ToolResultEvent.class);
        assertEquals("call-1", result.id());
        assertFalse(result.isError(), "只读工具应成功");
        assertTrue(result.output().toString().contains("echo:hi"),
                "工具结果应原样进入事件，实际：" + result.output());

        assertEquals(2, REQUEST_BODIES.size());
        assertTrue(REQUEST_BODIES.get(0).contains("\"tools\""), "首轮请求应下发工具目录");
        JsonNode secondReq = Json.parse(REQUEST_BODIES.get(1));
        boolean assistantHasToolCall = false;
        boolean toolResultPaired = false;
        for (JsonNode m : secondReq.path("messages")) {
            if ("assistant".equals(m.path("role").asText()) && m.path("tool_calls").isArray()) {
                assistantHasToolCall = "call-1".equals(
                        m.path("tool_calls").get(0).path("id").asText());
            }
            if ("tool".equals(m.path("role").asText())) {
                toolResultPaired = "call-1".equals(m.path("tool_call_id").asText());
            }
        }
        assertTrue(assistantHasToolCall, "第二轮请求必须携带含 call-1 的 assistant.tool_calls");
        assertTrue(toolResultPaired, "第二轮请求必须携带 tool_call_id=call-1 的 tool 结果消息");
    }

    // ------------------------------------------------------------------
    // 3. 步边界中止：循环开始即检测到取消 → ErrorEvent(用户取消)，不发任何 HTTP 请求
    // ------------------------------------------------------------------
    @Test
    void abortedBeforeStep_emitsCancelErrorAndSkipsLlmRequest() throws Exception {
        AgentLoopOptions options = new AgentLoopOptions().aborted(() -> true);

        List<AgentEvent> events = runLoop(new ToolRegistry(), options, "你好");

        ErrorEvent error = first(events, ErrorEvent.class);
        assertTrue(error.message().contains("用户取消请求"));
        assertTrue(none(events, DoneEvent.class), "取消后不应出现 Done");
        assertTrue(REQUEST_BODIES.isEmpty(), "取消后不应发出 LLM HTTP 请求");
    }

    // ------------------------------------------------------------------
    // 4. 服务端 5xx（maxRetries=0 立即失败）→ ErrorEvent，无 Done
    // ------------------------------------------------------------------
    @Test
    void http500_emitsErrorEvent() throws Exception {
        RESPONSES.add(new Resp(500, "{\"error\":{\"message\":\"boom\"}}"));

        List<AgentEvent> events = runLoop(new ToolRegistry(), new AgentLoopOptions(), "你好");

        ErrorEvent error = first(events, ErrorEvent.class);
        assertTrue(error.message().contains("HTTP 500"), "错误信息应包含状态码，实际：" + error.message());
        assertTrue(error.message().contains("boom"), "应透出服务端错误 message，实际：" + error.message());
        assertTrue(none(events, DoneEvent.class));
    }

    // ------------------------------------------------------------------
    // 5. 最大步数软收尾：工作步用尽后追加无工具收尾轮，收尾请求不带 tools 字段，
    //    模型纯文本总结后 Done（不再硬终止 ErrorEvent）。
    // ------------------------------------------------------------------
    @Test
    void maxStepsReached_wrapUpCallOmitsToolsAndCompletes() throws Exception {
        RESPONSES.add(new Resp(200, sseToolCall("call-1", "echo", "{\"q\":\"hi\"}")));
        RESPONSES.add(new Resp(200, sseText("这是最终结论")));
        EchoTool echo = new EchoTool();
        AgentLoopOptions options = new AgentLoopOptions().maxSteps(1);

        List<AgentEvent> events = runLoop(registryWith(echo), options, "回显一下");

        assertLastIsDone(events);
        assertEquals(1, echo.calls.get(), "工作步只应执行一次工具");
        assertEquals(2, REQUEST_BODIES.size());
        String wrapUpRequest = REQUEST_BODIES.get(1);
        assertFalse(wrapUpRequest.contains("\"tools\""), "收尾轮不得下发工具定义");
        assertTrue(wrapUpRequest.contains("已达最大步数上限"), "收尾轮前应注入收尾指令");
        assertEquals("这是最终结论", joinedText(events));
    }

    // ------------------------------------------------------------------
    // 6. 死循环熔断：连续同工具同参数，先注入一次警示，仍不修正则终止。
    //    步序（DOOM_LOOP_THRESHOLD=3）：执行 1、执行 2、警示不执行、执行 4、
    //    执行 5、第 6 次命中熔断 → 工具共执行 4 次。
    // ------------------------------------------------------------------
    @Test
    void repeatedIdenticalToolCall_warnsOnceThenTerminates() throws Exception {
        for (int i = 0; i < 6; i++) {
            RESPONSES.add(new Resp(200, sseToolCall("call-loop", "echo", "{\"q\":\"x\"}")));
        }
        EchoTool echo = new EchoTool();
        AgentLoopOptions options = new AgentLoopOptions().maxSteps(10);

        List<AgentEvent> events = runLoop(registryWith(echo), options, "重复回显");

        ErrorEvent error = first(events, ErrorEvent.class);
        assertTrue(error.message().contains("死循环"), "应报死循环终止，实际：" + error.message());
        assertTrue(none(events, DoneEvent.class), "熔断后不应出现 Done");
        assertEquals(4, echo.calls.get(), "警示步不执行工具，总执行次数应为 4");
        boolean warned = REQUEST_BODIES.stream().anyMatch(b -> b.contains("[系统警示]"));
        assertTrue(warned, "终止前应先注入一次系统警示");
    }

    // ------------------------------------------------------------------
    // 7. 中途插话：步边界 drain 的用户指示作为 user 消息注入当次请求
    // ------------------------------------------------------------------
    @Test
    void interjectionAtBoundary_injectedIntoRequest() throws Exception {
        RESPONSES.add(new Resp(200, sseText("收到")));
        AtomicInteger drains = new AtomicInteger();
        AgentLoopOptions options = new AgentLoopOptions()
                .interjections(() -> drains.getAndIncrement() == 0 ? "顺便看下日志" : null);

        List<AgentEvent> events = runLoop(new ToolRegistry(), options, "你好");

        assertLastIsDone(events);
        String firstRequest = REQUEST_BODIES.get(0);
        assertTrue(firstRequest.contains("[用户中途指示]"), "应带插话前缀");
        assertTrue(firstRequest.contains("顺便看下日志"), "插话正文应进入请求");
    }

    // ------------------------------------------------------------------
    // 8. 未知工具：错误被吃成 tool 结果回灌，循环继续并正常收尾
    // ------------------------------------------------------------------
    @Test
    void unknownTool_errorResultFedBackAndLoopContinues() throws Exception {
        RESPONSES.add(new Resp(200, sseToolCall("call-x", "ghost", "{}")));
        RESPONSES.add(new Resp(200, sseText("好的")));

        List<AgentEvent> events = runLoop(new ToolRegistry(), new AgentLoopOptions(), "调个不存在的工具");

        ToolResultEvent result = first(events, ToolResultEvent.class);
        assertTrue(result.isError());
        assertEquals("ghost", result.toolName());
        assertTrue(result.output().path("error").asText().contains("未知工具"),
                "错误结果应说明未知工具，实际：" + result.output());
        assertLastIsDone(events);
        assertEquals(2, REQUEST_BODIES.size());
        assertTrue(REQUEST_BODIES.get(1).contains("未知工具"),
                "第二次请求应携带错误 tool 结果供模型纠偏");
    }

    // ------------------------------------------------------------------
    // 测试基建
    // ------------------------------------------------------------------

    private static LlmProviderConfig provider() {
        // maxRetries=0：错误立即抛出，不引入指数退避等待
        return LlmProviderConfig.builder("test-model", baseUrl, "test-key").maxRetries(0).build();
    }

    private static ToolRegistry registryWith(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static List<AgentEvent> runLoop(ToolRegistry registry, AgentLoopOptions options,
                                            String userMessage) throws Exception {
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        UIMessage msg = new UIMessage("user", List.of(new TextPart(userMessage)));
        AgentLoop.agentLoop(List.of(msg), provider(), registry, options, events::add);
        return events;
    }

    /** 只读回显工具（READ 类别在权限引擎直接放行），记录执行次数与入参。 */
    private static final class EchoTool implements Tool {
        final AtomicInteger calls = new AtomicInteger();

        @Override public String name() { return "echo"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public String description() { return "回显输入（测试用）"; }
        @Override public JsonNode inputSchema() {
            ObjectNode schema = Json.object();
            schema.put("type", "object");
            schema.putObject("properties").putObject("q").put("type", "string");
            schema.putArray("required").add("q");
            return schema;
        }
        @Override public Object execute(JsonNode input, ToolContext ctx) {
            calls.incrementAndGet();
            return "echo:" + input.path("q").asText();
        }
    }

    // ---- SSE 脚本构造 ----

    private static String data(JsonNode chunk) {
        return "data: " + Json.stringify(chunk) + "\n\n";
    }

    private static ObjectNode choice(ObjectNode delta, String finishReason) {
        ObjectNode c = Json.object().put("index", 0);
        c.set("delta", delta);
        if (finishReason == null) {
            c.putNull("finish_reason");
        } else {
            c.put("finish_reason", finishReason);
        }
        return c;
    }

    /** 一段纯文本流式响应（content chunk + stop chunk 带 usage + [DONE]）。 */
    private static String sseText(String text) {
        ObjectNode first = Json.object();
        first.putArray("choices").add(choice(Json.object().put("content", text), null));
        ObjectNode last = Json.object();
        last.putArray("choices").add(choice(Json.object(), "stop"));
        last.set("usage", Json.object()
                .put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15));
        return data(first) + data(last) + "data: [DONE]\n\n";
    }

    /** 一次工具调用响应（参数一次性到齐）+ finish_reason=tool_calls + [DONE]。 */
    private static String sseToolCall(String id, String name, String argsJson) {
        ObjectNode fn = Json.object().put("name", name).put("arguments", argsJson);
        ObjectNode tc = Json.object().put("index", 0).put("id", id).put("type", "function");
        tc.set("function", fn);
        ObjectNode delta = Json.object();
        delta.putArray("tool_calls").add(tc);
        ObjectNode first = Json.object();
        first.putArray("choices").add(choice(delta, null));
        ObjectNode last = Json.object();
        last.putArray("choices").add(choice(Json.object(), "tool_calls"));
        return data(first) + data(last) + "data: [DONE]\n\n";
    }

    // ---- 事件断言辅助 ----

    private static String joinedText(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextDeltaEvent t) sb.append(t.content());
        }
        return sb.toString();
    }

    private static void assertLastIsDone(List<AgentEvent> events) {
        assertFalse(events.isEmpty(), "至少应收到一个事件");
        assertTrue(events.get(events.size() - 1) instanceof DoneEvent,
                "最后一个事件应为 Done，实际：" + events.get(events.size() - 1));
    }

    private static boolean any(List<AgentEvent> events, Class<? extends AgentEvent> type) {
        return events.stream().anyMatch(type::isInstance);
    }

    private static boolean none(List<AgentEvent> events, Class<? extends AgentEvent> type) {
        return events.stream().noneMatch(type::isInstance);
    }

    private static <T extends AgentEvent> T first(List<AgentEvent> events, Class<T> type) {
        return events.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("期望事件 " + type.getSimpleName() + "，实际：" + events));
    }
}
