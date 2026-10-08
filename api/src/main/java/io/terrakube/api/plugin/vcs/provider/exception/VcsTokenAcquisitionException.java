package io.terrakube.api.plugin.vcs.provider.exception;

import java.time.Duration;

// Thrown by a VCS provider's token service when acquiring/refreshing an access token fails
// against the provider's HTTP API. The message is always a safe, non-null, pre-built
// classification string - never the raw response body or any header/value that could carry a
// token/secret. The cause (the original HTTP/network exception) is preserved for logs.
public class VcsTokenAcquisitionException extends Exception {

    private final boolean retryable;
    private final Duration retryAfter;

    public VcsTokenAcquisitionException(String safeMessage, Throwable cause, boolean retryable) {
        this(safeMessage, cause, retryable, null);
    }

    public VcsTokenAcquisitionException(String safeMessage, Throwable cause, boolean retryable, Duration retryAfter) {
        super(safeMessage != null ? safeMessage : "VCS token acquisition failed", cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }

    // Non-null only when the provider told us explicitly how long to wait (e.g. a 429's
    // Retry-After header, or GitHub's X-RateLimit-Reset). Null means "use the caller's own
    // backoff schedule".
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
