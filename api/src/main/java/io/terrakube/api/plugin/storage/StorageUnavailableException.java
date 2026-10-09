package io.terrakube.api.plugin.storage;

/**
 * Thrown when a storage backend cannot fulfill a request due to a genuine failure (network,
 * auth, throttling, a partial write, etc) rather than the object genuinely not existing. Signals
 * to callers that the failure is retryable and must not be masked as an empty/successful
 * response - mirrors the same exception in the registry module's storage layer.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
