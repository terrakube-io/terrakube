package io.terrakube.api.plugin.scheduler.trigger;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Keeps run trigger dispatch metric names in one place, matching {@code JobReconciliationMetrics}'s convention. */
@Component
public class RunTriggerDispatchMetrics {

    private final MeterRegistry registry;

    public RunTriggerDispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A workspace had no state history to compare, so qualification fell back to the flow-type heuristic. */
    public void stateIdentityFallback() {
        registry.counter("terrakube.run.trigger.state.fallback").increment();
    }
}
