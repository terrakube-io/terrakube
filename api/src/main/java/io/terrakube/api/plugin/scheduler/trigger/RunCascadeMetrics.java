package io.terrakube.api.plugin.scheduler.trigger;

import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper so the cascade metric names live in one place, same reasoning and naming
 * convention as {@link io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationMetrics}.
 */
@Component
public class RunCascadeMetrics {

    private final MeterRegistry registry;

    public RunCascadeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void cascadeStarted() {
        registry.counter("terrakube.cascade.started").increment();
    }

    public void cascadeStatusChanged(RunCascadeStatus newStatus) {
        registry.counter("terrakube.cascade.status.transitions", "status", newStatus.name()).increment();
    }

    public void nodeRetried() {
        registry.counter("terrakube.cascade.node.retried").increment();
    }

    public void nodeResumed(boolean dispatched) {
        registry.counter("terrakube.cascade.node.resumed", "dispatched", String.valueOf(dispatched)).increment();
    }

    public void cascadeCancelled() {
        registry.counter("terrakube.cascade.cancelled").increment();
    }

    public void nodeBlocked() {
        registry.counter("terrakube.cascade.node.blocked").increment();
    }
}
