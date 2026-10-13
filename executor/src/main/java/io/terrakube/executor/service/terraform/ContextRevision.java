package io.terrakube.executor.service.terraform;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Revision for context patch entries. The API applies an entry only when its revision is higher
 * than the one it last applied for the same path, so a revision must order every write for a path.
 */
public final class ContextRevision {

    private static final AtomicLong LAST = new AtomicLong();

    private ContextRevision() {
    }

    /**
     * Strictly increasing within this JVM. The max with wall-clock microseconds is load-bearing: a
     * newer pod re-running a step must outrank the revisions the previous pod wrote, and a
     * long-lived pod must not fall behind wall clock after a burst of writes.
     */
    public static long next() {
        return LAST.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis() * 1000));
    }
}
