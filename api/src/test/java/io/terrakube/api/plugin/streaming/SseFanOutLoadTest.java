package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Redis read rate for a job must scale with the number of active jobs, not the number of
 * viewers: 100 people watching one run share a single {@code XREAD} loop.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SseFanOutLoadTest {

    @Mock
    RedisStreamReader reader;

    @Mock
    JobStatusCache jobStatusCache;

    @Test
    @Timeout(20)
    void oneRedisLoopServesManyViewersOfTheSameJob() throws InterruptedException {
        LogsProperties properties = new LogsProperties();
        properties.setSseMaxConnections(1000);
        properties.setSseJobIdleTimeout(Duration.ofMillis(20));
        properties.setSseMaxStreamDuration(Duration.ofSeconds(30));

        AtomicInteger readAfterCalls = new AtomicInteger(0);
        when(jobStatusCache.isTerminal("42")).thenReturn(false);
        when(reader.readAfterOnce(any(), any())).thenReturn(List.of());
        when(reader.readAfter(any(), any(), any())).thenAnswer(invocation -> {
            readAfterCalls.incrementAndGet();
            Thread.sleep(20); // simulate the Redis XREAD BLOCK
            return List.of();
        });

        JobLogBroadcasterRegistry registry = new JobLogBroadcasterRegistry(reader, jobStatusCache, properties);

        for (int i = 0; i < 100; i++) {
            registry.subscribe("42", "stepA", RecordId.of("0-0"));
        }

        assertEquals(1, registry.activeJobs());
        assertEquals(100, registry.activeConnections());

        Thread.sleep(500);

        // ~500ms / 20ms per read ~= 25 reads for the single shared loop. Per-viewer would be 100x that.
        int calls = readAfterCalls.get();
        assertTrue(calls < 100, "shared loop made " + calls + " reads - expected far fewer than one-per-viewer");
    }
}
