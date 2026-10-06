package io.terrakube.api.rs.workspace.trigger;

/** How a destination workspace with more than one enabled upstream edge waits on them. */
public enum RunTriggerSynchronizationMode {
    /** Today's behavior: every qualifying upstream run enqueues its own downstream run. */
    EACH,
    /** The first upstream success releases the destination; later ones in the same cascade don't. */
    ANY,
    /** The destination waits for every reachable upstream parent to succeed. */
    ALL
}
