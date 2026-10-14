package com.aye.ai.permission;

import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.tool.*;
import com.aye.ai.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PermissionModesTest {
    private PermissionDecision decide(String tool, ToolCategory category, String command, String mode) {
        var input = Json.object().put("command", command).put("path", "/opt/demo");
        return PermissionEngine.evaluatePermission(new PermissionContext(tool, input,
                "all_confirm".equals(mode) ? TrustLevel.PARANOID : TrustLevel.STANDARD,
                List.of(), List.of(), command, false, "/workspace", mode, category));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reboot", "rm -rf /opt/demo", "apt remove openssh-server", "touch /tmp/demo"})
    void readonlyDeniesModificationBeforeAnyConfirmation(String command) {
        for (String tool : List.of("run_ssh", "run_ssh_async")) {
            assertInstanceOf(PermissionDecision.Deny.class,
                    decide(tool, ToolCategory.EXECUTE, command, "readonly"));
        }
    }

    @Test
    void readonlyDeniesCriticalSftpDeletion() {
        assertInstanceOf(PermissionDecision.Deny.class,
                decide("sftp_remove", ToolCategory.DESTRUCTIVE, "", "readonly"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rm -rf /", "mkfs.ext4 /dev/sda", "iptables -F", "git reset --hard"})
    void syncAndAsyncSshHaveIdenticalSafetyDecisions(String command) {
        for (String mode : List.of("readonly", "write_confirm", "all_confirm")) {
            assertEquals(decide("run_ssh", ToolCategory.EXECUTE, command, mode).getClass(),
                    decide("run_ssh_async", ToolCategory.EXECUTE, command, mode).getClass());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"readonly", "write_confirm", "all_confirm"})
    void readonlyQueriesFollowTheModeForBothSshTools(String mode) {
        for (String tool : List.of("run_ssh", "run_ssh_async")) {
            Class<? extends PermissionDecision> expected = "all_confirm".equals(mode)
                    ? PermissionDecision.Confirm.class : PermissionDecision.Allow.class;
            assertInstanceOf(expected,
                    decide(tool, ToolCategory.EXECUTE, "ls /tmp", mode));
        }
    }

    @Test
    void readonlyNeverOffersApprovalOrExecutesDangerousTool() {
        var confirmed = new AtomicInteger();
        var executed = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "run_ssh"; }
            public ToolCategory category() { return ToolCategory.EXECUTE; }
            public String description() { return "Test only; never executes a command"; }
            public JsonNode inputSchema() { return JsonSchema.object().build(); }
            public String resolveCommandText(JsonNode input) { return "reboot"; }
            public Object execute(JsonNode input, ToolContext ctx) { return executed.incrementAndGet(); }
        };
        var registry = new ToolRegistry();
        registry.register(tool);
        var ctx = new ToolContext().sessionId("test").cwd("/workspace")
                .trustLevel(TrustLevel.STANDARD).executionMode("readonly")
                .waitForUserConfirm((callId, confirmId, name, message, tags, input) -> {
                    confirmed.incrementAndGet();
                    return true;
                });
        var result = ToolExecutor.executeToolCalls(List.of(new LlmToolCall("call", "run_ssh", "{}")), registry, ctx);
        assertTrue(result.get(0).isError());
        assertEquals(0, confirmed.get());
        assertEquals(0, executed.get());
    }
}
