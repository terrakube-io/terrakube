package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.JobStatus;

import java.util.List;

/**
 * Outcome of one {@link JobReconciliationService#reconcile} call (or one {@code report()} entry).
 * {@code targetStatus} is non-null only for a terminal-transition {@code derivedOutcome}.
 */
public record ReconciliationResult(
        int jobId,
        JobStatus currentStatus,
        DerivedOutcome derivedOutcome,
        JobStatus targetStatus,
        ReconciliationDisposition disposition,
        List<StepEvidence> evidence) {

    public record StepEvidence(int stepNumber, JobStatus status) {}

    public enum ReconciliationDisposition {
        /** A terminal transition was committed. */
        APPLIED,
        /** The deriver's rule 1 - job was already terminal, nothing to do. */
        ALREADY_TERMINAL,
        /** Would transition to {@code targetStatus}; not applied (dry-run). */
        DRY_RUN,
        /** ANOMALY or RETAIN_WAITING_APPROVAL - held for an operator, no transition. */
        HELD_ANOMALY,
        /** Re-read after the row lock found pending steps again - a racing dispatch owns it. */
        SKIPPED_HAS_WORK,
        /** Quartz contention during trigger removal; job state is durable, retried next pass. */
        RACE
    }
}
