package io.terrakube.registry.plugin.storage;

/**
 * Thrown when a storage backend cannot fulfill a request due to a transient failure (S3 timeout,
 * connectivity, throttling, etc). Signals to callers that the failure is retryable and must not be
 * masked as an empty/successful response - see the module-download resilience design.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
