package io.terrakube.api.plugin.storage;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageRetryMetricsTest {

    @Test
    void recordsASuccessTimerWithoutIncrementingFailuresOrRetrying() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StorageRetryMetrics storageRetryMetrics = new StorageRetryMetrics(registry);
        AtomicInteger calls = new AtomicInteger();

        String result = storageRetryMetrics.withRetry("aws", "read", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, calls.get(), "a successful call must not retry");
        assertNull(registry.find("terrakube.api.storage.failures").counter(), "no failure counter should be created on success");
        Timer successTimer = registry.find("terrakube.api.storage.duration")
                .tag("backend", "aws").tag("operation", "read").tag("outcome", "success").timer();
        assertEquals(1, successTimer.count());
    }

    @Test
    void retriesThreeTimesThenIncrementsTheFailureCounterAndThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StorageRetryMetrics storageRetryMetrics = new StorageRetryMetrics(registry);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(StorageUnavailableException.class, () -> storageRetryMetrics.withRetry("gcp", "write", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }));

        assertEquals(3, calls.get());
        assertEquals(1.0, registry.counter("terrakube.api.storage.failures", "backend", "gcp", "operation", "write").count());
        Timer failureTimer = registry.find("terrakube.api.storage.duration")
                .tag("backend", "gcp").tag("operation", "write").tag("outcome", "failure").timer();
        assertEquals(1, failureTimer.count());
    }
}
