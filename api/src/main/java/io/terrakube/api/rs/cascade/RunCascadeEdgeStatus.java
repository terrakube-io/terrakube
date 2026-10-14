package io.terrakube.api.rs.cascade;

/** Resolution of a dependency within one execution, independent of a workspace job's outcome. */
public enum RunCascadeEdgeStatus {
    PENDING, SATISFIED, SKIPPED, BLOCKED
}
