package io.terrakube.api.plugin.context;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Records how long {@code /context/v1} storage reads and writes take and how often they fail, so a
 * slow or unavailable object store is observable rather than surfacing to the executor as an
 * unhandled 500. Labelled by {@code operation} (read|write) and {@code outcome} (success|failure)
 * only - never by job, step, or context content.
 */
@Slf4j
@Component
public class ContextStorageMetrics {

    @FunctionalInterface
    public interface StorageCall<T> {
        T call() throws IOException;
    }

    private final MeterRegistry meterRegistry;

    public ContextStorageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T time(String operation, StorageCall<T> call) throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return call.call();
        } catch (IOException | RuntimeException e) {
            outcome = "failure";
            meterRegistry.counter("terrakube.api.context.storage.failures", "operation", operation).increment();
            log.warn("Context storage {} failed: {}", operation, e.getMessage());
            throw e;
        } finally {
            sample.stop(Timer.builder("terrakube.api.context.storage.duration")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}
