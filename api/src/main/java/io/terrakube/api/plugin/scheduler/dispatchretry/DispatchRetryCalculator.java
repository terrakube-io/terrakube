package io.terrakube.api.plugin.scheduler.dispatchretry;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.springframework.stereotype.Component;

import io.terrakube.api.rs.job.Job;
import lombok.AllArgsConstructor;

/**
 * Pure decision function for the bounded dispatch-retry budget (issues #3665/#3666). No I/O:
 * callers (ScheduleJob) persist the decision's fields onto the Job themselves.
 */
@Component
@AllArgsConstructor
public class DispatchRetryCalculator {

    private final DispatchRetryProperties properties;

    public DispatchRetryDecision decide(Job job, Date now, Duration providerRetryAfterHint) {
        int newFailureCount = job.getDispatchFailureCount() + 1;
        Date firstFailureAt = job.getDispatchFirstFailureAt() != null ? job.getDispatchFirstFailureAt() : now;
        long elapsedMinutes = ChronoUnit.MINUTES.between(firstFailureAt.toInstant(), now.toInstant());

        if (newFailureCount >= properties.getMaxAttempts() || elapsedMinutes >= properties.getMaxElapsedMinutes()) {
            return new DispatchRetryDecision(true, newFailureCount, firstFailureAt, null);
        }
        long delaySeconds = backoffSeconds(newFailureCount, providerRetryAfterHint);
        return new DispatchRetryDecision(false, newFailureCount, firstFailureAt,
                Date.from(now.toInstant().plusSeconds(delaySeconds)));
    }

    // attempt is always < properties.getMaxAttempts() here - decide() returns exhausted before
    // reaching this call otherwise - so the shift stays well within range (mirrors the same
    // bounded-attempt exponential backoff in WorkspaceService.getRateLimitRetryDelayMillis).
    private long backoffSeconds(int attempt, Duration providerHint) {
        long computed = providerHint != null
                ? providerHint.getSeconds()
                : properties.getInitialBackoffSeconds() * (1L << (attempt - 1));
        return Math.min(computed, properties.getMaxBackoffSeconds());
    }
}
