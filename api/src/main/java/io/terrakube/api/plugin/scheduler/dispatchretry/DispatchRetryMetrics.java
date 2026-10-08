package io.terrakube.api.plugin.scheduler.dispatchretry;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the bounded dispatch-retry budget (issues #3665/#3666). Dot-named to match the
 * repo convention (see {@link io.terrakube.api.plugin.scheduler.reconciliation.SchedulerQueueMetrics}).
 */
@Component
public class DispatchRetryMetrics {

    private final MeterRegistry meterRegistry;

    public DispatchRetryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void failureClassified(String classification) {
        meterRegistry.counter("terrakube.scheduler.dispatch.failures", "classification", classification).increment();
    }

    public void retryScheduled() {
        meterRegistry.counter("terrakube.scheduler.dispatch.retry.scheduled").increment();
    }

    public void budgetExhausted() {
        meterRegistry.counter("terrakube.scheduler.dispatch.retry.budget.exhausted").increment();
    }
}
