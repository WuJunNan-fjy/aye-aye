package com.aye.ai.session;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.message.UIMessageToLlm;
import com.aye.ai.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复盘中止 turn 的历史折叠：assistant/message（含 tool-call blocks）已落盘、
 * tool/result 未齐时 turn/end(aborted) 已闭合 —— 冷恢复不介入（非孤儿 turn），
 * {@code deriveUIMessages()} 必须自行补齐配对，否则下一轮喂模 400。
 */
class SessionEventLogToolPairingTest {

    private static ArrayNode blocksWithTwoToolCalls() {
        ArrayNode blocks = Json.array();
        ObjectNode b1 = blocks.addObject();
        b1.put("kind", "tool-call").put("callId", "c1").put("name", "run_ssh")
                .set("input", Json.object().put("command", "ls"));
        ObjectNode b2 = blocks.addObject();
        b2.put("kind", "tool-call").put("callId", "c2").put("name", "sftp_read")
                .set("input", Json.object().put("path", "/etc/hostname"));
        return blocks;
    }

    @Test
    @DisplayName("aborted turn 缺失的工具结果在折叠时补 interrupted，转换后满足协议")
    void abortedTurnBalancedByFolding() {
        SessionEventLog log = new SessionEventLog("s1");
        log.turnStarted(1, "deepseek-chat", "write_confirm");
        log.userMessage(1, "查一下服务器", SessionEventType.SOURCE_USER);
        log.stepStarted(1, 1);
        log.assistantMessage(1, 1, blocksWithTwoToolCalls(), null, "deepseek-chat", null, null);
        // 只回来 c1 的结果（并行批中途被取消），c2 悬空；turn/end(aborted) 正常闭合
        log.toolResult(1, 1, "c1", "run_ssh", Json.toNode("ok"), false,
                null, null, null, false);
        log.stepEnded(1, 1);
        log.turnEnded(1, SessionEventType.REASON_ABORTED, "deepseek-chat", "write_confirm", null);

        // turn 已闭合：冷恢复不应再改动日志
        assertFalse(log.balanceInterrupted(), "已闭合 turn 不属于冷恢复范围");

        List<UIMessage> folded = log.deriveUIMessages();
        // user + assistant 两条
        UIMessage assistant = folded.get(1);
        assertEquals("assistant", assistant.role());
        long callCount = assistant.parts().stream()
                .filter(p -> p instanceof UIPart.ToolCallPart).count();
        long resultCount = assistant.parts().stream()
                .filter(p -> p instanceof UIPart.ToolResultPart).count();
        assertEquals(2, callCount);
        assertEquals(2, resultCount, "缺失的 c2 必须补合成结果");

        // 端到端：转换为 LLM 消息后无悬空调用，c2 结果带 interrupted 文案
        List<LlmMessage> llm = UIMessageToLlm.convert(folded);
        assertEquals("assistant", llm.get(1).role());
        assertEquals(2, llm.get(1).toolCalls().size());
        assertEquals("c1", llm.get(2).toolCallId());
        assertEquals("c2", llm.get(3).toolCallId());
        assertTrue(llm.get(3).content().contains("interrupted"));
    }

    @Test
    @DisplayName("正常 completed turn 不受补全逻辑影响")
    void completedTurnUntouched() {
        SessionEventLog log = new SessionEventLog("s2");
        log.turnStarted(1, "deepseek-chat", "write_confirm");
        log.userMessage(1, "看磁盘", SessionEventType.SOURCE_USER);
        log.stepStarted(1, 1);
        ArrayNode blocks = Json.array();
        blocks.addObject().put("kind", "tool-call").put("callId", "c1").put("name", "run_ssh")
                .set("input", Json.object().put("command", "df -h"));
        log.assistantMessage(1, 1, blocks, null, "deepseek-chat", null, null);
        log.toolResult(1, 1, "c1", "run_ssh", Json.toNode("disk ok"), false,
                null, null, null, false);
        log.stepEnded(1, 1);
        log.turnEnded(1, SessionEventType.REASON_COMPLETED, "deepseek-chat", "write_confirm", null);

        List<UIMessage> folded = log.deriveUIMessages();
        UIMessage assistant = folded.get(1);
        long synthetic = assistant.parts().stream()
                .filter(p -> p instanceof UIPart.ToolResultPart trp
                        && trp.output() != null
                        && trp.output().path("interrupted").asBoolean(false))
                .count();
        assertEquals(0, synthetic, "真实结果在场时不应追加合成结果");
        List<LlmMessage> llm = UIMessageToLlm.convert(folded);
        assertEquals("disk ok", llm.get(2).content());
    }
}
