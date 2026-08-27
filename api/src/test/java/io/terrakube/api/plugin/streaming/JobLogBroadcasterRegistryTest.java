package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import org.junit.jupiter.api.BeforeEach;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobLogBroadcasterRegistryTest {

    @Mock
    RedisStreamReader reader;

    @Mock
    JobStatusCache jobStatusCache;

    LogsProperties properties;
    JobLogBroadcasterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new LogsProperties();
        properties.setSseMaxConnections(2);
        properties.setSseJobIdleTimeout(Duration.ofMillis(20));
        properties.setSseMaxStreamDuration(Duration.ofSeconds(30));
        when(reader.readAfterOnce(any(), any())).thenReturn(List.of());
        when(reader.readAfter(any(), any(), any())).thenReturn(List.of());
        registry = new JobLogBroadcasterRegistry(reader, jobStatusCache, properties);
    }

    @Test
    @Timeout(10)
    void twoSubscribersOnTheSameJobShareOneBroadcaster() {
        when(jobStatusCache.isTerminal("42")).thenReturn(false);

        registry.subscribe("42", "stepA", RecordId.of("0-0"));
        registry.subscribe("42", "stepB", RecordId.of("0-0"));

        assertEquals(1, registry.activeJobs());
        assertEquals(2, registry.activeConnections());
    }

    @Test
    @Timeout(10)
    void rejectsSubscriptionsBeyondThePerPodCap() {
        when(jobStatusCache.isTerminal("42")).thenReturn(false);

        registry.subscribe("42", "stepA", RecordId.of("0-0"));
        registry.subscribe("42", "stepB", RecordId.of("0-0"));

        assertThrows(SseCapacityExceededException.class,
                () -> registry.subscribe("42", "stepC", RecordId.of("0-0")));
        assertEquals(2, registry.activeConnections());
    }

    @Test
    @Timeout(10)
    void doesAOneShotCatchUpReadForANewSubscriber() {
        when(jobStatusCache.isTerminal("42")).thenReturn(false);

        registry.subscribe("42", "stepA", RecordId.of("7-0"));

        verify(reader).readAfterOnce(eq("42"), eq(RecordId.of("7-0")));
    }

    @Test
    @Timeout(10)
    void removesTheBroadcasterWhenTheJobBecomesTerminal() {
        when(jobStatusCache.isTerminal("42")).thenReturn(true);

        registry.subscribe("42", "stepA", RecordId.of("0-0"));

        await().atMost(Duration.ofSeconds(5)).until(() -> registry.activeJobs() == 0);
    }
}
