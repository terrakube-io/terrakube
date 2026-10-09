package io.terrakube.api.plugin.scheduler.trigger;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.stereotype.Component;

/**
 * Bounds on the run trigger dispatch engine. The defaults are deliberately conservative: a
 * dependency graph that needs more than these numbers is more likely a mistake than a
 * legitimate topology, and both limits exist so that a mistake degrades loudly instead of
 * saturating the executors.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.run-trigger")
public class RunTriggerProperties {

    /** Kill switch. When false no downstream run is ever enqueued; edges stay configurable. */
    private boolean enabled = true;

    /**
     * How deep a chain of triggered runs may go. Phase 2 rejects cycles when an edge is
     * created, but two concurrent creations can each pass validation and together close a
     * loop; this is the runtime net that stops such a loop from running forever.
     */
    private int maxCascadeDepth = 10;

    /**
     * How many enabled outbound edges a single workspace may have, enforced by
     * {@link WorkspaceGraphValidationService#validateFanOutLimit} at edge creation. Renamed
     * from {@code max-dependents-per-apply}: that name described a dispatch-time bound, but
     * this one is checked at creation, not per apply. See {@link #getMaxDependentsPerApply()}
     * for the old name, kept bound to this same field so an already-configured value doesn't
     * silently change meaning on upgrade.
     */
    private int maxOutboundTriggersPerWorkspace = 20;

    /**
     * @deprecated renamed to {@code io.terrakube.run-trigger.max-outbound-triggers-per-workspace}
     * - same field, old name kept only so a value set before the rename keeps working.
     */
    @Deprecated
    @DeprecatedConfigurationProperty(
            reason = "This bound moved from dispatch-time truncation to edge-creation-time rejection",
            replacement = "io.terrakube.run-trigger.max-outbound-triggers-per-workspace")
    public int getMaxDependentsPerApply() {
        return maxOutboundTriggersPerWorkspace;
    }

    /** @deprecated see {@link #getMaxDependentsPerApply()}. */
    @Deprecated
    public void setMaxDependentsPerApply(int maxDependentsPerApply) {
        this.maxOutboundTriggersPerWorkspace = maxDependentsPerApply;
    }

    /**
     * Kill switch for just the event worker, independent of {@link #enabled}. With this false,
     * events still get written but nothing claims them - unlike {@link #enabled}, which stops
     * new events from being written at all.
     */
    private boolean eventWorkerEnabled = true;

    /** How many claimed attempts a RunTriggerEvent gets before it is marked FAILED for good. */
    private int eventMaxAttempts = 10;

    /**
     * How long a claimed (PROCESSING) event may run before the stuck-row sweep assumes the
     * claiming replica died mid-dispatch and reclaims it.
     */
    private int eventLeaseSeconds = 60;

    /** First retry delay for a failed event, before exponential backoff and jitter are applied. */
    private int eventBackoffInitialSeconds = 5;

    /** Ceiling on the backoff delay between retries, however many attempts have been made. */
    private int eventBackoffMaxSeconds = 900;

    /** How many due events the poller claims and dispatches in one tick. */
    private int eventPollerBatchSize = 200;

    /** How long a terminal (PROCESSED/FAILED) event row is kept before the retention sweep deletes it. */
    private int eventRetentionDays = 90;
}
