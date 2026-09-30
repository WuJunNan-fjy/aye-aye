package com.aye.server.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalJobManagerTest {

    @TempDir
    Path tmp;

    private final boolean windows =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Test
    void successfulJob() throws Exception {
        LocalJobManager mgr = new LocalJobManager(new LocalExecService());
        String jobId = mgr.start(windows ? "echo hello-world" : "echo hello-world", tmp, "test");
        LocalJobManager.JobView view = mgr.snapshot(jobId, 10_000, 4000);
        assertNotNull(view);
        assertEquals(LocalJobManager.Status.DONE, view.status());
        assertEquals(0, view.exitCode());
        assertTrue(view.stdout().contains("hello-world"));
    }

    @Test
    void nonZeroExitCaptured() throws Exception {
        LocalJobManager mgr = new LocalJobManager(new LocalExecService());
        String jobId = mgr.start(windows ? "exit 7" : "exit 7", tmp, "test");
        LocalJobManager.JobView view = mgr.snapshot(jobId, 10_000, 4000);
        assertNotNull(view);
        assertEquals(LocalJobManager.Status.DONE, view.status());
        assertEquals(7, view.exitCode());
    }

    @Test
    void unknownJobReturnsNull() throws Exception {
        LocalJobManager mgr = new LocalJobManager(new LocalExecService());
        assertNull(mgr.snapshot("missing-id", 0, 1000));
    }
}
