package io.terrakube.api.rs.cascade;

/** Status of one workspace's position within a cascade's reachable subgraph. */
public enum RunCascadeNodeStatus {
    /** Reachable, but still waiting on a parent (ALL) or not yet picked up. */
    PENDING,
    /** Every dependency this node's synchronizationMode requires has resolved; eligible to run. */
    READY,
    /** An attempt has been dispatched and is in flight. */
    RUNNING,
    /** Terminal: the dispatched attempt succeeded. */
    SUCCEEDED,
    /** Terminal: attempts exhausted without success. */
    FAILED,
    /** Terminal: never ran because a dependency it needed under ALL/ANY didn't resolve in its favor. */
    SKIPPED,
    /** Waiting on an operator - e.g. a BLOCK on_destroy policy rejected the run upstream of this node. */
    BLOCKED
}
