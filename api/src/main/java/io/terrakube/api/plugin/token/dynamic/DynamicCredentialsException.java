package io.terrakube.api.plugin.token.dynamic;

/**
 * Credential preparation for a job failed. The message is safe to show to the user and to store on
 * the job: it never contains tokens, request or response bodies.
 */
public class DynamicCredentialsException extends RuntimeException {
    public DynamicCredentialsException(String message) {
        super(message);
    }

    public DynamicCredentialsException(String message, Throwable cause) {
        super(message, cause);
    }
}
