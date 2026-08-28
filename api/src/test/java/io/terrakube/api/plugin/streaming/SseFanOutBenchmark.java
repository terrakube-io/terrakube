package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Not a CI gate (name ends "Benchmark"). Run explicitly:
 *   mvn -pl api test -Dtest=SseFanOutBenchmark
 *
 * Shows the Redis read rate for a single job as the number of concurrent viewers grows. On the old
 * code every viewer ran its own XREAD loop (+ a findById every 2s); here one loop per job serves
 * all of them, so the count stays flat.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SseFanOutBenchmark {

    @Mock
    RedisStreamReader reader;

    @Mock
    JobStatusCache jobStatusCache;

    @Test
    void redisReadRateVsViewerCount() throws Exception {
        LogsProperties props = new LogsProperties();
        props.setSseMaxConnections(10_000);
        props.setSseJobIdleTimeout(Duration.ofMillis(20));
        props.setSseMaxStreamDuration(Duration.ofSeconds(30));

        when(jobStatusCache.isTerminal(any())).thenReturn(false);
        when(reader.readAfterOnce(any(), any())).thenReturn(List.of());

        // One counter per jobId - a broadcaster loop leaked from an earlier iteration keeps reading
        // its own key, so counting per-key keeps each row clean.
        java.util.Map<String, AtomicInteger> readsByJob = new java.util.concurrent.ConcurrentHashMap<>();
        when(reader.readAfter(any(), any(), any())).thenAnswer(inv -> {
            readsByJob.computeIfAbsent(inv.getArgument(0), k -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(20);
            return List.of();
        });

        System.out.printf("%n  viewers │ XREAD calls in 1s │ old design (per-viewer loop)%n");
        System.out.printf("  ────────┼───────────────────┼─────────────────────────────%n");

        int[] counts = { 1, 10, 50, 100, 250 };
        for (int idx = 0; idx < counts.length; idx++) {
            int viewers = counts[idx];
            String jobId = "job-" + idx;

            JobLogBroadcasterRegistry registry = new JobLogBroadcasterRegistry(reader, jobStatusCache, props);
            for (int i = 0; i < viewers; i++) {
                registry.subscribe(jobId, "stepA", RecordId.of("0-0"));
            }

            readsByJob.put(jobId, new AtomicInteger());
            Thread.sleep(1000);
            int shared = readsByJob.get(jobId).get();

            System.out.printf("  %7d │ %17d │ ~%,d%n", viewers, shared, (long) shared * viewers);
        }

        System.out.printf("%n  \"per-viewer equivalent\" = what the old one-loop-per-viewer design would have done.%n");
    }
}
