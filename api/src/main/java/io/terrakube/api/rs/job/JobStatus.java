package io.terrakube.api.rs.job;

import java.util.EnumSet;
import java.util.Set;

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
    NeverExecuted;

    private static final Set<JobStatus> TERMINAL = EnumSet.of(
            completed, noChanges, rejected, cancelled, failed);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
