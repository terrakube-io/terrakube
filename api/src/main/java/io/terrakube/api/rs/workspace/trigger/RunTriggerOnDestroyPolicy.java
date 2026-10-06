package io.terrakube.api.rs.workspace.trigger;

/** What a destroy on the source workspace does to this edge's destination. */
public enum RunTriggerOnDestroyPolicy {
    /** Default: a destroy is a state-changing run like any other, so it dispatches normally. */
    TRIGGER,
    /** Enqueue a plan on the destination instead of an apply, to surface the drift without acting on it. */
    PLAN_ONLY,
    /** Reject the destroy until this edge is disabled or the destination is handled. */
    BLOCK,
    /** The destination is unaffected; the edge only reacts to non-destroy state changes. */
    IGNORE
}
