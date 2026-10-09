package io.terrakube.api.plugin.scheduler.trigger;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metric publisher for the durable {@code RunTriggerEvent} outbox lifecycle, mirroring
 * {@link RunTriggerDispatchMetrics} and {@code JobReconciliationMetrics}.
 */
@Component
public class RunTriggerEventMetrics {

    private final MeterRegistry registry;

    public RunTriggerEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void processed() {
        registry.counter("terrakube.run.trigger.event.processed").increment();
    }

    public void retried() {
        registry.counter("terrakube.run.trigger.event.retried").increment();
    }

    public void failed() {
        registry.counter("terrakube.run.trigger.event.failed").increment();
    }

    public void stuckReclaimed(int count) {
        registry.counter("terrakube.run.trigger.event.stuck_reclaimed").increment(count);
    }

    public void pruned(int count) {
        registry.counter("terrakube.run.trigger.event.pruned").increment(count);
    }
}
