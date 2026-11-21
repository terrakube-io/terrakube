package io.terrakube.api.plugin.scheduler.job.tcl.executor;

// Thrown when the executor pool could not be reached or a transport/gateway failure prevented
// receiving an acknowledgement. Pre-acceptance failures (mayHaveBeenAccepted == false: connection
// refused, DNS failure, HTTP 503 capacity gate) never entered an executor's job submission path,
// so callers can release the step's dispatch claim immediately. Ambiguous post-send failures
// (mayHaveBeenAccepted == true: read/response timeout, premature close after write, HTTP 502/504)
// may have already been accepted by an executor, so callers retain the dispatch claim.
public class ExecutorUnavailableException extends ExecutionException {
    private final boolean mayHaveBeenAccepted;

    public ExecutorUnavailableException(Throwable cause) {
        this(cause != null ? cause.getMessage() : null, cause, false);
    }

    public ExecutorUnavailableException(String message) {
        this(message, null, false);
    }

    public ExecutorUnavailableException(String message, Throwable cause, boolean mayHaveBeenAccepted) {
        super(message, cause);
        this.mayHaveBeenAccepted = mayHaveBeenAccepted;
    }

    public boolean mayHaveBeenAccepted() {
        return mayHaveBeenAccepted;
    }
}
