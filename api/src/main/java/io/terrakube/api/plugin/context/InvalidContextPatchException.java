package io.terrakube.api.plugin.context;

/**
 * A {@code /context/v1/{jobId}/patch} body that fails validation. Mapped by {@link ContextController}
 * to {@code 400 Bad Request} before the write lock is taken. Messages never echo the client's paths.
 */
public class InvalidContextPatchException extends RuntimeException {
    public InvalidContextPatchException(String message) {
        super(message);
    }
}
