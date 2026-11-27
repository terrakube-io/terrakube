package io.terrakube.api.plugin.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UploadSpoolTest {

    @TempDir
    Path tempDir;

    @Test
    void spoolsTheExactBytesIntoTheDirectory() throws IOException {
        byte[] data = new byte[200_000];
        new java.util.Random(1).nextBytes(data);

        Path spooled = UploadSpool.spool("id1", new ByteArrayInputStream(data), tempDir);

        assertEquals(tempDir, spooled.getParent());
        assertArrayEquals(data, Files.readAllBytes(spooled));
        UploadSpool.delete(spooled);
        assertFalse(Files.exists(spooled));
    }

    @Test
    void aFailedReadThrowsAndLeavesNoTempFile() throws IOException {
        InputStream failing = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 10) {
                    return 'x';
                }
                throw new IOException("connection reset");
            }
        };

        StorageUnavailableException e = assertThrows(StorageUnavailableException.class,
                () -> UploadSpool.spool("id1", failing, tempDir));

        assertEquals("Unable to read uploaded content for id1", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }
}
