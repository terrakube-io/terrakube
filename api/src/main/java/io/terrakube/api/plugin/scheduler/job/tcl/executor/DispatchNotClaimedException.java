package io.terrakube.api.plugin.scheduler.job.tcl.executor;

// Thrown just before submission when this worker no longer owns the job's execution lock, or the
// step was already claimed by another attempt. Nothing was sent to an executor; the scheduler
// retries on its next tick instead of failing the job.
public class DispatchNotClaimedException extends ExecutorUnavailableException {
    public DispatchNotClaimedException(String message) {
        super(message);
    }
}
