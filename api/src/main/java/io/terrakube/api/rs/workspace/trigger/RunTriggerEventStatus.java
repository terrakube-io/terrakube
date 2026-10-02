package io.terrakube.api.rs.workspace.trigger;

public enum RunTriggerEventStatus {
    /** Due to be claimed. Also the state a retryable failure returns to with nextAttemptAt set. */
    PENDING,
    /** Claimed by a worker; delivery of this job's run triggers is in flight. */
    PROCESSING,
    /** Terminal: dispatch ran to completion (whether or not it found anything to dispatch). */
    PROCESSED,
    /** Terminal: attempts exhausted without a successful run. */
    FAILED
}
