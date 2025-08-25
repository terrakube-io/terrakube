package io.terrakube.api.rs.job;

public enum JobStatus {
    pending,
    waitingApproval,
    approved,
    queue,
    running,
    completed,
    noChanges,
    notExecuted,
    rejected,
    cancelled,
    failed,
    unknown,
    NeverExecuted
}
