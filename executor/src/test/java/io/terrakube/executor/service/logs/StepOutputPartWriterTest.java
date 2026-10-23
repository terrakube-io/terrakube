package io.terrakube.executor.service.logs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StepOutputPartWriterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> parts = new ArrayList<>();

    private final StepOutputPartWriter.Sink collecting = (number, data) -> {
        assertEquals(parts.size() + 1, number, "parts are written in order");
        try (var in = data.open()) {
            byte[] bytes = in.readAllBytes();
            assertEquals(data.length(), bytes.length);
            parts.add(new String(bytes, StandardCharsets.UTF_8));
        }
    };

    private StepOutputPartWriter writer(StepOutputBuffer buffer, StepOutputPartWriter.Sink sink, long partBytes, int maxParts) {
        return new StepOutputPartWriter(buffer, sink, partBytes, Duration.ofSeconds(30), maxParts, "test", now::get);
    }

    @Test
    void writesAPartOnceTheSizeIsReached() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 10, 100);

        buffer.append("12345");
        writer.tick();
        assertTrue(parts.isEmpty());

        buffer.append("67890abc");
        writer.tick();
        assertEquals(List.of("1234567890"), parts);

        buffer.append("defghijklm");
        writer.tick();
        assertEquals(List.of("1234567890", "abcdefghij"), parts);
    }

    @Test
    void aBurstIsSplitIntoPartsOfTheConfiguredSize() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 4, 100);

        buffer.append("aaaabbbbcccdd");
        writer.tick();

        assertEquals(List.of("aaaa", "bbbb", "cccd"), parts);
    }

    @Test
    void writesASmallTailAfterTheIntervalButNotBefore() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 1000, 100);

        buffer.append("short");
        now.addAndGet(29_000);
        writer.tick();
        assertTrue(parts.isEmpty());

        now.addAndGet(1_000);
        writer.tick();
        assertEquals(List.of("short"), parts);

        now.addAndGet(60_000);
        writer.tick();
        assertEquals(1, parts.size(), "nothing new, nothing written");
    }

    @Test
    void aShortStepWritesNothing() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 1000, 100);

        buffer.append("done quickly");
        writer.tick();

        assertTrue(parts.isEmpty());
        assertEquals(0, writer.stop());
    }

    @Test
    void readsFromTheSpillFileToo(@org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) {
        StepOutputBuffer buffer = new StepOutputBuffer(8, spool);
        StepOutputPartWriter writer = writer(buffer, collecting, 6, 100);

        buffer.append("first line\n");
        buffer.append("second line\n");
        writer.tick();

        assertFalse(buffer.isInMemory());
        assertEquals("first line\nsecond line\n".substring(0, 18), String.join("", parts));
        assertEquals(3, parts.size());
    }

    @Test
    void aFailedPartIsTriedAgainFromTheSameOffsetAfterAPause() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        int[] attempts = {0};
        StepOutputPartWriter.Sink flaky = (number, data) -> {
            if (attempts[0]++ < 2) {
                throw new IOException("storage unavailable");
            }
            collecting.put(number, data);
        };
        StepOutputPartWriter writer = writer(buffer, flaky, 4, 100);
        buffer.append("abcdefgh");

        writer.tick();
        assertEquals(1, attempts[0]);
        writer.tick();
        assertEquals(1, attempts[0], "paused after a failure");

        now.addAndGet(5_000);
        writer.tick();
        assertEquals(2, attempts[0]);

        now.addAndGet(30_000);
        writer.tick();
        assertEquals(List.of("abcd", "efgh"), parts);
        assertEquals(2, writer.stop());
    }

    @Test
    void stopsAtTheMaximumNumberOfParts() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        // 8 parts allowed, one tier is a single part, so the sizes are 2, 4, 8, 16 ...
        StepOutputPartWriter writer = writer(buffer, collecting, 2, 2);

        buffer.append("a".repeat(100));
        now.addAndGet(60_000);
        writer.tick();

        assertEquals(2, parts.size());
        assertEquals(2, writer.stop());
        buffer.append("more");
        now.addAndGet(60_000);
        writer.tick();
        assertEquals(2, parts.size());
    }

    @Test
    void partSizesGrowInTiersAndAreCapped() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 1024, 80);

        assertEquals(1024, writer.partSize(1));
        assertEquals(1024, writer.partSize(10));
        assertEquals(2048, writer.partSize(11));
        assertEquals(4096, writer.partSize(21));
        assertEquals(StepOutputPartWriter.MAX_PART_BYTES,
                writer(buffer, collecting, 1024 * 1024, 80).partSize(80));
    }

    @Test
    void aStoppedWriterWritesNothingMore() {
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter writer = writer(buffer, collecting, 2, 100);
        buffer.append("aabb");
        writer.tick();
        assertEquals(2, writer.stop());

        buffer.append("ccdd");
        writer.tick();

        assertEquals(2, parts.size());
    }

    @Test
    void theBackgroundThreadWritesPartsWhileTheStepRuns() throws Exception {
        StepOutputBuffer buffer = new StepOutputBuffer();
        java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(2);
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        StepOutputPartWriter writer = new StepOutputPartWriter(buffer, (number, data) -> {
            seen.add(new String(data.open().readAllBytes(), StandardCharsets.UTF_8));
            written.countDown();
        }, 4, Duration.ofSeconds(30), 100, "test");
        writer.start();
        buffer.append("12345678");

        assertTrue(written.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, writer.stop());
        assertEquals(List.of("1234", "5678"), seen);
    }

    @Test
    void countsPartsWrittenAndFailed() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        StepOutputBuffer buffer = new StepOutputBuffer();
        int[] attempts = {0};
        StepOutputPartWriter writer = writer(buffer, (number, data) -> {
            if (attempts[0]++ == 0) {
                throw new IOException("down");
            }
        }, 4, 100).withMetrics(registry);
        buffer.append("abcd");

        writer.tick();
        now.addAndGet(10_000);
        writer.tick();

        assertEquals(1.0, registry.get("terrakube.executor.output.parts").tag("result", "failed").counter().count());
        assertEquals(1.0, registry.get("terrakube.executor.output.parts").tag("result", "written").counter().count());
    }
}
