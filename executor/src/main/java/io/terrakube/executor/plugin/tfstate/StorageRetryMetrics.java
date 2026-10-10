package io.terrakube.executor.plugin.tfstate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * Shared bounded-retry + Micrometer instrumentation for the single underlying storage call in
 * each {@code TerraformState} backend (plan file read/write, state history write, job output
 * write). Mirrors {@code io.terrakube.api.plugin.context.ContextStorageMetrics}'s shape - tagged
 * by {@code backend} (aws|azure|gcp|local) and {@code operation}, outcome success|failure -
 * rather than inventing a new one. Not-found-vs-error classification stays in each caller, since
 * what "not found" means differs per backend; this only retries and times the genuinely-failing
 * call.
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
            meterRegistry.counter("terrakube.executor.storage.failures", "backend", backend, "operation", operation).increment();
            log.error("{} {} failed after {} attempts: {}", backend, operation, MAX_ATTEMPTS, lastFailure.getMessage());
            throw new TerraformStateUnavailableException(backend + " " + operation + " failed after " + MAX_ATTEMPTS + " attempts", lastFailure);
        } finally {
            sample.stop(Timer.builder("terrakube.executor.storage.duration")
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
