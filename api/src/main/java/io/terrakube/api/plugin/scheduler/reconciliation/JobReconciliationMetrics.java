package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.rs.job.JobStatus;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper so the reconciliation metric names live in one place. Names are dot-delimited
 * (repo convention); Prometheus renders them with underscores and a {@code _total} suffix on
 * counters, matching the design doc §3.9 table.
 */
@Component
public class JobReconciliationMetrics {

    private final MeterRegistry registry;

    public JobReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void observedZeroPendingNonTerminal(JobStatus currentStatus) {
        registry.counter("terrakube.scheduler.zero.pending.nonterminal",
                "status", currentStatus.name()).increment();
    }

    public void reconciled(JobStatus targetStatus) {
        registry.counter("terrakube.scheduler.zero.pending.reconciliations",
                "outcome", targetStatus.name()).increment();
    }

    public void anomaly() {
        registry.counter("terrakube.scheduler.reconciliation.anomalies").increment();
    }

    public void quartzTriggerRecreated() {
        registry.counter("terrakube.scheduler.quartz.trigger.recreated").increment();
    }

    public void quartzTriggerRace() {
        registry.counter("terrakube.scheduler.quartz.trigger.races").increment();
    }
}
