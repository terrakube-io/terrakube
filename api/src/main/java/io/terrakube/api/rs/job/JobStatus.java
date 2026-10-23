package io.terrakube.api.rs.job;

import java.util.Collections;
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

    // A job in one of these statuses is not finished and the jobs created after it wait behind it.
    private static final Set<JobStatus> HOLDS_THE_QUEUE = Collections.unmodifiableSet(EnumSet.of(
            pending, waitingApproval, approved, queue, running));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean holdsTheQueue() {
        return HOLDS_THE_QUEUE.contains(this);
    }

    public static Set<JobStatus> holdingTheQueue() {
        return HOLDS_THE_QUEUE;
    }
}
