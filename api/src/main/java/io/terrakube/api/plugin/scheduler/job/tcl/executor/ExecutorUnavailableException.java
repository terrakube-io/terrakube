package io.terrakube.api.plugin.scheduler.job.tcl.executor;

// Thrown when the executor pool could not be reached at all (connection refused, timed out,
// no ready Service endpoints) as opposed to the executor being reachable and rejecting the
// job. Callers can catch this separately to retry instead of failing the job outright.
public class ExecutorUnavailableException extends ExecutionException {
    public ExecutorUnavailableException(Throwable cause) {
        super(cause);
    }

    public ExecutorUnavailableException(String message) {
        super(message);
    }
}
