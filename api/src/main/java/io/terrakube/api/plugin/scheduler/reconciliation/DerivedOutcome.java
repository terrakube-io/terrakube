package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.JobStatus;

import java.util.Optional;

/**
 * Result of {@link JobTerminalStateDeriver#derive}. Only FAILED/CANCELLED/REJECTED/COMPLETED
 * carry a target status the service should transition the job to.
 */
public enum DerivedOutcome {
    ALREADY_TERMINAL(null),
    FAILED(JobStatus.failed),
    CANCELLED(JobStatus.cancelled),
    REJECTED(JobStatus.rejected),
    COMPLETED(JobStatus.completed),
    RETAIN_WAITING_APPROVAL(null),
    ANOMALY(null);

    private final JobStatus target;

    DerivedOutcome(JobStatus target) {
        this.target = target;
    }

    public Optional<JobStatus> targetStatus() {
        return Optional.ofNullable(target);
    }

    public boolean isTerminalTransition() {
        return target != null;
    }
}
