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
}
