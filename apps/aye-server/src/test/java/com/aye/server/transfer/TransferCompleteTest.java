package com.aye.server.transfer;

import com.aye.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransferCompleteTest {

    @TempDir
    Path tmp;

    private TransferService service() {
        // openAssembledStream 不触碰 sessions/sftp，可传 null
        return new TransferService(tmp.toString(), null, null);
    }

    private TransferManifest manifest(int chunks, long size, int chunkSize) {
        return new TransferManifest("uid1234567", "s", "/opt", "a.bin",
                size, chunks, chunkSize, "error", System.currentTimeMillis(), "RECEIVING");
    }

    @Test
    void incompleteChunksRejected() {
        TransferManifest m = manifest(2, 10L, 5);
        ApiException ex = assertThrows(ApiException.class,
                () -> service().openAssembledStream(tmp, m));
        assertEquals(409, ex.getStatus());
    }

    @Test
    void corruptedChunkSizeRejected() throws Exception {
        TransferManifest m = manifest(2, 10L, 5);
        Files.write(tmp.resolve("chunk_0"), new byte[]{1, 2, 3, 4, 5});
        Files.write(tmp.resolve("chunk_1"), new byte[]{9}); // 应为 5 字节
        ApiException ex = assertThrows(ApiException.class,
                () -> service().openAssembledStream(tmp, m));
        assertEquals(400, ex.getStatus());
    }

    @Test
    void assembledStreamOpensWhenComplete() throws Exception {
        TransferManifest m = manifest(2, 10L, 5);
        Files.write(tmp.resolve("chunk_0"), new byte[]{1, 2, 3, 4, 5});
        Files.write(tmp.resolve("chunk_1"), new byte[]{6, 7, 8, 9, 10});
        try (SequenceInputStream seq = service().openAssembledStream(tmp, m)) {
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
                    seq.readAllBytes());
        }
    }
}
