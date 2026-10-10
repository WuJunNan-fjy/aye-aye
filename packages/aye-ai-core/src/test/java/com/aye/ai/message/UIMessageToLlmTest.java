package com.aye.ai.message;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UIMessageToLlm} 协议配对不变量测试：
 * 每条带 tool_calls 的 assistant 后必须为每个 tool_call_id 恰好跟一条 tool 消息。
 * 历史来源不可信（中止 / 异常 / 冷恢复边界），转换器是最后防线（DeepSeek 400 回归用例）。
 */
class UIMessageToLlmTest {

    private static UIPart.ToolCallPart call(String id, String name) {
        ObjectNode input = Json.object().put("q", id);
        return new UIPart.ToolCallPart(id, name, input);
    }

    private static UIPart.ToolResultPart result(String id, String text) {
        return new UIPart.ToolResultPart(id, Json.toNode(text), false);
    }

    /** 断言整条序列满足 OpenAI 工具协议：tool_call 与 tool 消息一一对应、顺序一致。 */
    private static void assertToolContract(List<LlmMessage> msgs) {
        for (int i = 0; i < msgs.size(); i++) {
            LlmMessage m = msgs.get(i);
            if ("assistant".equals(m.role()) && m.toolCalls() != null) {
                int n = m.toolCalls().size();
                for (int k = 0; k < n; k++) {
                    LlmMessage reply = msgs.get(i + 1 + k);
                    assertEquals("tool", reply.role(),
                            "assistant tool_calls 后第 " + k + " 条必须是 tool 消息");
                    assertEquals(m.toolCalls().get(k).id(), reply.toolCallId(),
                            "tool 消息 tool_call_id 必须与调用声明顺序一一对应");
                }
            }
            if ("tool".equals(m.role())) {
                assertNotNull(m.toolCallId(), "tool 消息必须带 tool_call_id");
            }
        }
    }

    @Test
    @DisplayName("折叠形状：结果挂在 assistant parts 内，正常配对")
    void foldedShapePaired() {
        List<UIMessage> in = List.of(
                new UIMessage("user", List.of(new UIPart.TextPart("做事"))),
                new UIMessage("assistant", List.of(
                        new UIPart.TextPart("好的"),
                        call("c1", "run_ssh"), result("c1", "ok1"),
                        call("c2", "sftp_read"), result("c2", "ok2"))),
                new UIMessage("assistant", List.of(new UIPart.TextPart("完成"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(List.of("user", "assistant", "tool", "tool", "assistant"),
                out.stream().map(LlmMessage::role).toList());
        assertEquals("好的", out.get(1).content());
    }

    @Test
    @DisplayName("Recorder 形状：assistant(calls) 与 role=tool 结果分两条 UIMessage")
    void recorderShapePaired() {
        List<UIMessage> in = List.of(
                new UIMessage("assistant", new ArrayList<>(List.of(
                        call("c1", "run_ssh"), call("c2", "run_ssh")))),
                new UIMessage("tool", List.of(result("c1", "ok1"), result("c2", "ok2"))),
                new UIMessage("user", List.of(new UIPart.TextPart("继续"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(List.of("assistant", "tool", "tool", "user"),
                out.stream().map(LlmMessage::role).toList());
    }

    @Test
    @DisplayName("中止场景（400 回归）：assistant tool_calls 后完全没有 tool 结果，"
            + "下一条是 user —— 必须插入合成 tool 响应")
    void abortedAfterToolCallsBeforeResults() {
        List<UIMessage> in = List.of(
                new UIMessage("user", List.of(new UIPart.TextPart("查一下"))),
                new UIMessage("assistant", new ArrayList<>(List.of(call("c1", "run_ssh")))),
                new UIMessage("user", List.of(new UIPart.TextPart("你好"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(List.of("user", "assistant", "tool", "user"),
                out.stream().map(LlmMessage::role).toList());
        assertEquals("c1", out.get(2).toolCallId());
        assertTrue(out.get(2).content().contains("interrupted"));
        // 末尾 user 仍在，且 assistant 的 content 为 null（纯工具调用）
        assertNull(out.get(1).content());
    }

    @Test
    @DisplayName("并行工具部分结果：2 个调用只回来 1 个，缺失的补 interrupted")
    void parallelPartialResults() {
        List<UIMessage> in = List.of(
                new UIMessage("assistant", new ArrayList<>(List.of(
                        call("c1", "sftp_read"), call("c2", "sftp_read"),
                        result("c1", "data")))),
                new UIMessage("user", List.of(new UIPart.TextPart("next"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(2, out.get(0).toolCalls().size());
        assertEquals("data", out.get(1).content());
        assertTrue(out.get(2).content().contains("interrupted"));
    }

    @Test
    @DisplayName("悬空调用在历史末尾：转换结束时也要闭合")
    void danglingCallsAtTail() {
        List<UIMessage> in = List.of(
                new UIMessage("user", List.of(new UIPart.TextPart("x"))),
                new UIMessage("assistant", new ArrayList<>(List.of(
                        call("c1", "run_ssh"), call("c2", "run_ssh")))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(List.of("user", "assistant", "tool", "tool"),
                out.stream().map(LlmMessage::role).toList());
    }

    @Test
    @DisplayName("孤立 tool 结果：合成配对 assistant(tool_calls) + tool")
    void orphanToolResult() {
        List<UIMessage> in = List.of(
                new UIMessage("user", List.of(new UIPart.TextPart("x"))),
                new UIMessage("tool", List.of(result("ghost", "late result"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        LlmMessage synth = out.get(1);
        assertEquals("assistant", synth.role());
        assertEquals(1, synth.toolCalls().size());
        assertEquals("ghost", synth.toolCalls().get(0).id());
        assertEquals("ghost", out.get(2).toolCallId());
        assertEquals("late result", out.get(2).content());
    }

    @Test
    @DisplayName("纯 reasoning / 交互记录 assistant 消息不下发（避免 content:null 无调用消息）")
    void displayOnlyAssistantDropped() {
        List<UIMessage> in = List.of(
                new UIMessage("assistant", List.of(
                        new UIPart.ReasoningPart("让我想想"),
                        new UIPart.InteractionConfirmPart(
                                "cf1", "run_ssh", "确认执行？", List.of(), null, true))),
                new UIMessage("assistant", List.of(new UIPart.TextPart("结论"))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertEquals(1, out.size());
        assertEquals("assistant", out.get(0).role());
        assertEquals("结论", out.get(0).content());
    }

    @Test
    @DisplayName("同 id 重复结果只保留第一条")
    void duplicateIdsHandled() {
        List<UIMessage> in = List.of(
                new UIMessage("assistant", new ArrayList<>(List.of(
                        call("c1", "run_ssh"),
                        result("c1", "first"),
                        result("c1", "second")))));

        List<LlmMessage> out = UIMessageToLlm.convert(in);
        assertToolContract(out);
        assertEquals(List.of("assistant", "tool"),
                out.stream().map(LlmMessage::role).toList());
        assertEquals("first", out.get(1).content());
    }
}
