package io.terrakube.executor.plugin.tfstate;

/**
 * Thrown when the executor exhausts its retries trying to deliver a state,
 * plan, or output payload to the Terrakube API. The message is user-facing —
 * it is written to the job step output so the run shows up as failed with a
 * concrete reason instead of silently losing data.
 */
public class StateUploadFailedException extends RuntimeException {

    public StateUploadFailedException(String message) {
        super(message);
    }

    public StateUploadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
