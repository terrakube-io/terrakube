package io.terrakube.api.plugin.storage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared bounded retry + Micrometer instrumentation for the four storage backends
 * (AWS/Azure/GCP/Local). Mirrors {@code executor/.../plugin/tfstate/StorageRetryMetrics} and the
 * existing {@code ContextStorageMetrics} shape - a genuine failure on the single underlying call
 * is retried, then thrown as {@link StorageUnavailableException} instead of silently collapsing
 * into the same result as a real "not found" (#3671).
 */
@Slf4j
@Component
public class StorageRetryMetrics {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {200, 500};

    private final MeterRegistry meterRegistry;

    public StorageRetryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @FunctionalInterface
    public interface RetryableCall<T> {
        T call() throws Exception;
    }

    public <T> T withRetry(String backend, String operation, RetryableCall<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        Exception lastFailure = null;
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    return action.call();
                } catch (Exception e) {
                    lastFailure = e;
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("{} {} attempt {} failed, retrying: {}", backend, operation, attempt, e.getMessage());
                        sleepBackoff(BACKOFF_MILLIS[attempt - 1]);
                    }
                }
            }
            outcome = "failure";
            meterRegistry.counter("terrakube.api.storage.failures", "backend", backend, "operation", operation).increment();
            log.error("{} {} failed after {} attempts: {}", backend, operation, MAX_ATTEMPTS, lastFailure.getMessage());
            throw new StorageUnavailableException(backend + " " + operation + " failed after " + MAX_ATTEMPTS + " attempts", lastFailure);
        } finally {
            sample.stop(Timer.builder("terrakube.api.storage.duration")
                    .tag("backend", backend)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
