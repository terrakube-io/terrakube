package io.terrakube.api.plugin.scheduler.dispatchretry;

import java.util.Date;

/**
 * Outcome of one {@link DispatchRetryCalculator#decide} call.
 * {@code nextRetryAt} is non-null only when {@code exhausted} is false.
 */
public record DispatchRetryDecision(boolean exhausted, int newFailureCount, Date firstFailureAt, Date nextRetryAt) {
}
