package io.terrakube.api.rs.cascade;

/** Status of one cascade, reduced from its nodes' statuses by the coordinator. */
public enum RunCascadeStatus {
    /** At least one node is still pending, ready, or running. */
    RUNNING,
    /** Every reachable node resolved, none failed or were blocked. */
    COMPLETED,
    /** Resolved, but at least one node failed or was skipped as a result. */
    DEGRADED,
    /** Waiting on an operator - an ALL join lost a parent, or a BLOCK policy rejected a destroy. */
    BLOCKED,
    /** An operator cancelled the cascade before every node resolved. */
    CANCELLED,
    /** The origin node itself failed, so nothing downstream was ever reachable. */
    FAILED
}
