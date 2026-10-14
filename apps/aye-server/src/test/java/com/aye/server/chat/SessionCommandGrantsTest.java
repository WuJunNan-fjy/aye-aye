package com.aye.server.chat;

import com.aye.ai.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCommandGrantsTest {
    private final SessionCommandGrants grants = new SessionCommandGrants();

    @Test
    void grantsOnlyMatchSameSessionModeWorkspaceToolAndCompleteInput() {
        var input = Json.object().put("command", "npm run build").put("purpose", "build");
        grants.prepare("session", "write_confirm", "project-a");
        grants.grant("session", "write_confirm", "project-a", "run_local_async", input);
        assertTrue(grants.isGranted("session", "write_confirm", "project-a", "run_local_async", input));
        assertFalse(grants.isGranted("other-session", "write_confirm", "project-a", "run_local_async", input));
        assertFalse(grants.isGranted("session", "all_confirm", "project-a", "run_local_async", input));
        assertFalse(grants.isGranted("session", "write_confirm", "project-b", "run_local_async", input));
        assertFalse(grants.isGranted("session", "write_confirm", "project-a", "run_ssh_async", input));
        assertFalse(grants.isGranted("session", "write_confirm", "project-a", "run_local_async",
                input.deepCopy().put("purpose", "different")));
    }

    @Test
    void changingScopeRevokesOldGrantsEvenWhenSwitchingBack() {
        var input = Json.object().put("command", "npm run build");
        for (String[] next : new String[][]{{"all_confirm", "project-a"}, {"write_confirm", "project-b"}}) {
            grants.prepare("session", "write_confirm", "project-a");
            grants.grant("session", "write_confirm", "project-a", "run_local_async", input);
            grants.prepare("session", next[0], next[1]);
            grants.prepare("session", "write_confirm", "project-a");
            assertFalse(grants.isGranted("session", "write_confirm", "project-a", "run_local_async", input));
        }
    }

    @Test
    void remoteHostAndAllArgumentsArePartOfTheGrant() {
        var input = Json.object().put("command", "echo hello").put("host_key", "host-a").put("tag", "a");
        grants.prepare("session", "write_confirm", "");
        grants.grant("session", "write_confirm", "", "run_ssh_async", input);
        assertFalse(grants.isGranted("session", "write_confirm", "", "run_ssh_async",
                input.deepCopy().put("host_key", "host-b")));
        assertFalse(grants.isGranted("session", "write_confirm", "", "run_ssh_async",
                input.deepCopy().put("tag", "b")));
        grants.prepare("session", "write_confirm", "");
        assertTrue(grants.isGranted("session", "write_confirm", "", "run_ssh_async", input));
        grants.clear("session");
        assertFalse(grants.isGranted("session", "write_confirm", "", "run_ssh_async", input));
    }
}
