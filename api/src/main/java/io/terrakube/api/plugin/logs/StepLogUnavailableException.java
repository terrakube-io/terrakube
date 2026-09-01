package io.terrakube.api.plugin.logs;

/**
 * Thrown when an archived step-log object read fails or times out. Mapped by
 * {@code TerraformOutputController} to a controlled {@code 503 Service Unavailable} with a
 * {@code Retry-After} hint - never a servlet {@code 500} - so a transient S3 problem stays local to
 * that step in the UI instead of triggering the application-wide backend-error page.
 */
public class StepLogUnavailableException extends RuntimeException {
    public StepLogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
