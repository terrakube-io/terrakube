package io.terrakube.executor.service.status;

/**
 * Raised by {@link UpdateJobStatus#setRunningStatus} when the job was cancelled after it was handed to this
 * executor but before it started. The caller stops without running the step or reporting a result.
 */
public class JobCancelledBeforeStartException extends RuntimeException {

    public JobCancelledBeforeStartException(String jobId) {
        super(String.format("Job %s was cancelled before it started", jobId));
    }
}
