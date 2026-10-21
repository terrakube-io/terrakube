package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StepOutputBufferTest {

    @TempDir
    Path spool;

    private long filesInSpool() throws IOException {
        try (Stream<Path> files = Files.list(spool)) {
            return files.count();
        }
    }

    @Test
    void smallOutputStaysInMemory() throws IOException {
        try (StepOutputBuffer buffer = new StepOutputBuffer(1024, spool)) {
            buffer.appendln("first");
            buffer.appendln("second");

            assertTrue(buffer.isInMemory());
            assertEquals("first\nsecond\n", buffer.toString());
            assertEquals(13, buffer.sizeInBytes());
            assertEquals(0, filesInSpool());
        }
    }

    @Test
    void outputPastTheLimitMovesToAFileAndKeepsEverything() throws IOException {
        StepOutputBuffer buffer = new StepOutputBuffer(64, spool);
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String line = "line " + i + " é中";
            buffer.appendln(line);
            expected.append(line).append('\n');
        }

        assertFalse(buffer.isInMemory());
        assertEquals(1, filesInSpool());
        assertEquals(expected.toString(), buffer.toString());
        assertEquals(expected.toString().getBytes(StandardCharsets.UTF_8).length, buffer.sizeInBytes());

        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        buffer.writeTo(copy);
        assertEquals(expected.toString(), copy.toString(StandardCharsets.UTF_8));
        buffer.close();
    }

    @Test
    void closingDeletesTheFile() throws IOException {
        StepOutputBuffer buffer = new StepOutputBuffer(8, spool);
        buffer.appendln("more than eight bytes");
        assertEquals(1, filesInSpool());

        buffer.close();

        assertEquals(0, filesInSpool());
        buffer.appendln("ignored after close");
        assertEquals("", buffer.toString());
    }

    @Test
    void streamCanBeOpenedMoreThanOnceWithASuffix() throws IOException {
        try (StepOutputBuffer buffer = new StepOutputBuffer(8, spool)) {
            buffer.appendln("0123456789");
            buffer.appendln("abc");

            for (int attempt = 0; attempt < 2; attempt++) {
                try (InputStream in = buffer.openStream("tail")) {
                    assertEquals("0123456789\nabc\ntail", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(19, StepOutputBuffer.sizeWith(buffer, "tail"));
        }
    }

    @Test
    void emptyBufferGivesEmptyStream() throws IOException {
        try (StepOutputBuffer buffer = new StepOutputBuffer(8, spool); InputStream in = buffer.openStream(null)) {
            assertEquals(0, in.readAllBytes().length);
        }
    }
}
