package com.aye.server.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferManifestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonRoundTrip() throws Exception {
        TransferManifest m = new TransferManifest(
                "abc_123-xyz", "sess-1", "/opt/app", "a.zip",
                12_000_000L, 3, 5_000_000, "error",
                System.currentTimeMillis(), "RECEIVING");
        String json = mapper.writeValueAsString(m);
        TransferManifest back = mapper.readValue(json, TransferManifest.class);
        assertEquals(m, back);
    }

    @Test
    void safeUploadIdRules() {
        assertTrue(TransferManifest.isSafeUploadId("abcd1234"));
        assertFalse(TransferManifest.isSafeUploadId("../evil"));   // 路径穿越
        assertFalse(TransferManifest.isSafeUploadId("a/b"));
        assertFalse(TransferManifest.isSafeUploadId("短"));        // 非 ASCII
    }

    @Test
    void safeFileNameRules() {
        assertTrue(TransferManifest.isSafeFileName("app.zip"));
        assertFalse(TransferManifest.isSafeFileName("a/b.zip"));
        assertFalse(TransferManifest.isSafeFileName(".."));
    }
}
