package io.terrakube.api.plugin.scheduler.trigger;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metric publisher for run trigger dispatch decisions.
 */
@Component
public class RunTriggerDispatchMetrics {

    private final MeterRegistry registry;

    public RunTriggerDispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void stateIdentityFallback() {
        registry.counter("terrakube.run.trigger.state.fallback").increment();
    }
}
