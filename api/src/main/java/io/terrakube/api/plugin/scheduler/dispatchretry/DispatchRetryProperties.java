package io.terrakube.api.plugin.scheduler.dispatchretry;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Rollout flags for the bounded dispatch-retry/backoff budget covering pre-submission dispatch
 * failures (issues #3665/#3666 - today's only source: VCS access-token acquisition). Mirrors the
 * phased-rollout shape of {@link io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationProperties}.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.api.scheduler.dispatch-retry")
public class DispatchRetryProperties {

    /**
     * Master switch. Off = today's behaviour exactly: every pre-submission dispatch failure is
     * instantly terminal, backoff columns are never written, and the admission-deferral guarded
     * queries are never consulted.
     */
    private boolean enabled = true;

    /**
     * Attempt cap: the Nth failure (1-indexed) that reaches this count fails the job terminally
     * instead of scheduling another retry.
     */
    private int maxAttempts = 8;

    /**
     * Elapsed-time cap, independent of {@link #maxAttempts}: once this many minutes have passed
     * since the first failure, the next failure is terminal regardless of attempt count.
     */
    private long maxElapsedMinutes = 60;

    /**
     * Base exponential backoff delay (attempt 1) used when the provider gave no retry hint.
     */
    private long initialBackoffSeconds = 15;

    /**
     * Ceiling applied to the computed exponential backoff AND to an honoured provider hint - a
     * job in backoff occupies the shared global FIFO head, so an operator-tunable ceiling
     * protects against a pathological provider hint parking the queue for hours.
     */
    private long maxBackoffSeconds = 900;

    /**
     * Phase-2 flag (only matters once {@link #enabled}): excludes a backoff-deferred job from
     * the *global* FIFO admission guard, so other workspaces stop waiting behind it. Off = the
     * failure/backoff is tracked and visible in run details, but the deferred job still blocks
     * other workspaces (conservative default for a first rollout phase).
     */
    private boolean admissionDeferralEnabled = true;

    // Shared by every FIFO call site that needs the 3-way admission-guard ladder
    // (ScheduleJob.isNextInDispatchOrder/wakeNextDispatchableJob, ExecutorAvailabilityListener)
    // so the "admissionGuardEnabled && dispatch-retry.enabled && admissionDeferralEnabled"
    // computation lives in one place instead of being repeated at each call site.
    public boolean isAdmissionDeferralActive(boolean admissionGuardEnabled) {
        return admissionGuardEnabled && enabled && admissionDeferralEnabled;
    }
}
