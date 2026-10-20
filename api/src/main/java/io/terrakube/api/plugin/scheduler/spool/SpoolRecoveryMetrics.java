package io.terrakube.api.plugin.scheduler.spool;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer metrics for structured output spooling and recovery sweeper operations.
 */
@Component
public class SpoolRecoveryMetrics {

    private final MeterRegistry registry;

    public SpoolRecoveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void stepSpooled(String phase) {
        registry.counter("terrakube.spool.steps.spooled", "phase", phase != null ? phase : "unknown").increment();
    }

    public void stepRecovered(String phase) {
        registry.counter("terrakube.spool.steps.recovered", "phase", phase != null ? phase : "unknown").increment();
    }

    public void stepExpired() {
        registry.counter("terrakube.spool.steps.expired").increment();
    }

    public void spoolCorrupted() {
        registry.counter("terrakube.spool.corrupted").increment();
    }

    public void leaseLost() {
        registry.counter("terrakube.spool.lease.lost").increment();
    }
}
