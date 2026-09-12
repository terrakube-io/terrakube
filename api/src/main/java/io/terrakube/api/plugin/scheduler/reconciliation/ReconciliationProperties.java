package io.terrakube.api.plugin.scheduler.reconciliation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Rollout flags for zero-pending job reconciliation and queue-liveness (design doc 2026-09-02).
 * The scheduler inline path (ScheduleJob delegating an out-of-steps job to the reconciliation
 * routine) is always active and not gated here - it replaces existing code. These flags gate
 * the proactive sweep remediation and the admission-query change.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.api.scheduler.reconciliation")
public class ReconciliationProperties {

    /**
     * Master switch: the 30s sweep calls the reconciliation routine, and the admin endpoint may
     * apply transitions. Off = the sweep does trigger/heartbeat repair only (today's behaviour);
     * the endpoint GET report still works. Does not affect the scheduler inline path.
     */
    private boolean sweepEnabled = true;

    /**
     * When {@link #sweepEnabled}: the sweep applies deterministic completed/failed/cancelled/
     * rejected transitions. Off = the sweep runs the routine in dry-run (metrics + logs only).
     * Anomalies are always held regardless.
     */
    private boolean autoRemediate = true;

    /**
     * Use the guarded executor-admission queries: a job with steps but no pending step no longer
     * counts as a FIFO-queue blocker.
     */
    private boolean admissionGuardEnabled = true;

    /**
     * Age (seconds) past which a non-terminal zero-pending job is flagged stale by the admin
     * report and the alert rule.
     */
    private int anomalyGraceSeconds = 300;
}
