package io.terrakube.executor.service.terraform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Revision for context patch entries. The API applies an entry only when its revision is higher
 * than the one it last applied for the same path, so a revision must order every write for a path.
 *
 * <p>Encoding: {@code executionEpoch * SCALE + seq}. The API stamps {@code executionEpoch} (its own
 * clock, in millis) when it dispatches a step, and {@code seq} counts this execution's writes. A
 * re-dispatched step therefore always outranks the previous execution whatever the executor clocks
 * say, and a zombie old execution always loses.
 */
public final class ContextRevision {

    public static final long SCALE = 1_000_000L;

    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private ContextRevision() {
    }

    /**
     * Starts an execution of a step of {@code jobId}. An epoch {@code <= 0} (an API that does not send
     * one) falls back to this executor's wall clock, which orders executions only as well as the
     * executor clocks agree. Never moves the counter backwards. A null job id is ignored, as in
     * {@link #end}: both run in the job's setup/teardown, which must not throw.
     */
    public static void begin(String jobId, long executionEpoch) {
        if (jobId == null) {
            return;
        }
        long epoch = executionEpoch > 0 ? executionEpoch : System.currentTimeMillis();
        COUNTERS.computeIfAbsent(jobId, k -> new AtomicLong()).accumulateAndGet(epoch * SCALE, Math::max);
    }

    /**
     * Strictly increasing per job. Without {@link #begin} the epoch is this executor's wall clock. A
     * null job id gets a wall-clock revision and no counter.
     */
    public static long next(String jobId) {
        if (jobId == null) {
            return System.currentTimeMillis() * SCALE;
        }
        return COUNTERS.computeIfAbsent(jobId, k -> new AtomicLong(System.currentTimeMillis() * SCALE))
                .incrementAndGet();
    }

    /**
     * Ends the execution that {@link #begin began} with {@code executionEpoch}; writes already minted
     * keep their revisions. A newer execution of the job that began meanwhile keeps its counter.
     */
    public static void end(String jobId, long executionEpoch) {
        if (jobId == null) {
            return;
        }
        long epoch = executionEpoch > 0 ? executionEpoch : System.currentTimeMillis();
        COUNTERS.computeIfPresent(jobId, (k, counter) -> counter.get() / SCALE > epoch ? counter : null);
    }
}
