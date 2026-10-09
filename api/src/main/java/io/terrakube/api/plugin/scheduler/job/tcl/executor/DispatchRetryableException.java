package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import java.time.Duration;

// Thrown when a pre-submission dispatch step (today: VCS access-token acquisition) fails with a
// transient/rate-limited provider error BEFORE the job ever reached the executor pool. Distinct
// from ExecutorUnavailableException (the executor pool itself is unreachable - unbounded
// retry-every-30s, must stay untouched by the dispatch-retry budget below) and from a plain
// ExecutionException (terminal failure, handled via ScheduleJob.errorJobAtStep): this carries an
// optional provider-supplied retry hint and is consumed only by ScheduleJob's bounded
// dispatch-retry backoff/budget.
public class DispatchRetryableException extends ExecutionException {

    private final Duration retryAfter;

    public DispatchRetryableException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    // Non-null only when the provider told us explicitly how long to wait. Null means "use the
    // caller's own exponential backoff schedule".
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
