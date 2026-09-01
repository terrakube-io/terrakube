package io.terrakube.api.plugin.context;

/**
 * Thrown when a {@code /context/v1} object-store read times out or fails. Mapped by
 * {@link ContextController} to a controlled {@code 503 Service Unavailable} with a retry hint -
 * never a servlet {@code 500}. Failures are not negative-cached.
 */
public class ContextUnavailableException extends RuntimeException {
    public ContextUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
