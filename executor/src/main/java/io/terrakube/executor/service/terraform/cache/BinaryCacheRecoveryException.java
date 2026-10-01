package io.terrakube.executor.service.terraform.cache;

import java.io.IOException;

/**
 * A known-invalid archive or binary couldn't be cleaned up, so the caller shouldn't fall back to
 * downloading anyway - that would just hit the same broken file again. Extends {@link IOException}
 * so it propagates through {@code ensureBinaryCached}/{@code executeTerraformInit} as a normal job
 * failure, without needing new exception plumbing.
 */
public class BinaryCacheRecoveryException extends IOException {

    public BinaryCacheRecoveryException(String message) {
        super(message);
    }

    public BinaryCacheRecoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
