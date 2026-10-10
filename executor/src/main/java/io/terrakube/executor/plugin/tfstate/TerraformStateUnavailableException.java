package io.terrakube.executor.plugin.tfstate;

/**
 * Thrown when a {@code TerraformState} backend cannot fulfill a request due to a genuine failure
 * (network, auth, throttling, a partial write, etc) rather than the object genuinely not
 * existing. Mirrors {@code io.terrakube.api.plugin.storage.StorageUnavailableException} - the
 * executor module can't reference that class directly since it lives in a separate Maven module.
 */
public class TerraformStateUnavailableException extends RuntimeException {

    public TerraformStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
