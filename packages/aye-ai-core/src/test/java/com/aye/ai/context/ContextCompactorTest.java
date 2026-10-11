package com.aye.ai.context;

import com.aye.ai.agent.AgentLoopOptions;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCompactor 触发阈值与消息结构测试。
 *
 * <p>provider 传 null：语义总结不可用，直接走抽取式压缩，无网络依赖、确定性强。
 * token 估算按 ASCII 4 字符 ≈ 1 token（见 {@link ContextCompactor#estimateTokens}）。
 */
class ContextCompactorTest {

    /** 构造一组：system + 两对长 user/assistant（早历史）+ 一条短 user（当前轮）。 */
    private static List<LlmMessage> longHistory() {
        String headUser = "x".repeat(2400); // 约 600 token
        String headAssistant = "y".repeat(2400);
        return List.of(
                LlmMessage.system("sys-prompt"),
                LlmMessage.user("HEAD-USER-A " + headUser),
                LlmMessage.assistant("HEAD-ASSISTANT-A " + headAssistant, null),
                LlmMessage.user("HEAD-USER-B " + headUser),
                LlmMessage.assistant("HEAD-ASSISTANT-B " + headAssistant, null),
                LlmMessage.user("当前轮的新问题")
        );
    }

    @Test
    void belowBudget_returnsSameInstance() {
        // 默认窗口 128K：约 2400 token 的历史远低于 72% 阈值，不压缩
        ContextCompactor compactor = new ContextCompactor(null, 0, true, () -> false);
        List<LlmMessage> messages = longHistory();

        assertSame(messages, compactor.compactBeforeCall(messages));
    }

    @Test
    void smallWindow_triggersExtractiveCompaction() {
        // 2000 token 窗口 → 阈值 1440（72%）；历史约 2400 token，必然触发
        ContextCompactor compactor = new ContextCompactor(null, 2000, true, () -> false);
        List<LlmMessage> messages = longHistory();
        long before = ContextCompactor.estimateTokens(messages);
        assertTrue(before > 1440, "前置条件：历史应超阈值，实际 " + before);

        List<LlmMessage> out = compactor.compactBeforeCall(messages);

        assertNotSame(messages, out);
        // 结构契约：[原始 system, 摘要消息, ...尾段]
        assertEquals("sys-prompt", out.get(0).content());
        assertEquals("user", out.get(1).role());
        assertTrue(out.get(1).content().startsWith("[历史上下文摘要"),
                "摘要消息必须带固定标记，实际：" + out.get(1).content().substring(0, Math.min(30, out.get(1).content().length())));
        // 抽取式保留早历史的原文前缀
        assertTrue(out.get(1).content().contains("HEAD-USER-A"));
        // 当前轮原始消息必须原样保留在尾段
        assertEquals("当前轮的新问题", out.get(out.size() - 1).content());
        assertTrue(ContextCompactor.estimateTokens(out) < before,
                "压缩后应更小：" + ContextCompactor.estimateTokens(out) + " vs " + before);
    }

    @Test
    void options_contextWindowTokensFlowsIntoCompactor() {
        // AgentLoopOptions 透传链路：目录里配置的小窗口必须真正生效（修复前永远按 128K 算）
        AgentLoopOptions options = new AgentLoopOptions().contextWindowTokens(2000);
        ContextCompactor compactor = ContextCompactor.from(options, null);
        List<LlmMessage> messages = longHistory();

        List<LlmMessage> out = compactor.compactBeforeCall(messages);
        assertNotSame(messages, out, "2000 token 窗口下约 2400 token 历史应触发压缩");
        assertTrue(out.get(1).content().startsWith("[历史上下文摘要"));
    }

    @Test
    void tooFewMessages_neverCompacts() {
        ContextCompactor compactor = new ContextCompactor(null, 100, true, () -> false);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s"),
                LlmMessage.user("z".repeat(2000)));
        assertSame(messages, compactor.compactBeforeCall(messages),
                "只有 system+单条消息时无法切轮次，必须原样返回");
    }
}
