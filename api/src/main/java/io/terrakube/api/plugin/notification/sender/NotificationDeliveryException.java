package io.terrakube.api.plugin.notification.sender;

import java.time.Duration;

public class NotificationDeliveryException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    public NotificationDeliveryException(String message) {
        this(message, null, true, null);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        this(message, cause, true, null);
    }

    public NotificationDeliveryException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, null);
    }

    public NotificationDeliveryException(String message, Throwable cause, boolean retryable, Duration retryAfter) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }

    // Non-null only when the destination told us explicitly how long to wait (e.g. a 429/503's
    // Retry-After header). Null means "use the standard attemptCount-based backoff".
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
