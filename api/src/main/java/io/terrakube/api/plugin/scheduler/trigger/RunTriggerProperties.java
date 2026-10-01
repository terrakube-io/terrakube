package io.terrakube.api.plugin.scheduler.trigger;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
     * How many dependents a single apply may fan out to. Beyond this the extra dependents
     * are skipped with a warning naming them, rather than the whole fan-out being dropped.
     */
    private int maxDependentsPerApply = 20;

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
