package io.terrakube.executor.service.terraform.structured;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot.Key;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, coalescing, retrying hand-off between the Terraform/OpenTofu process-output reader
 * threads and job-context persistence.
 *
 * <ul>
 *   <li>{@link #submit} never blocks and never throws - it coalesces by {@code (jobId, stepId,
 *       phase)} so only the newest unsaved snapshot for a step survives, and evicts an older
 *       snapshot (preferring a non-final one for the same job/step) when full.</li>
 *   <li>A single worker thread does the GET/merge/POST + SSE via {@link StructuredSnapshotPersister},
 *       retrying with capped exponential backoff.</li>
 *   <li>On retry-budget exhaustion the newest snapshot per key is <em>retained</em> and retried by a
 *       separate scheduled thread on a slow jittered cadence until it persists or the recovery
 *       retention period expires - so a brief context-store outage does not permanently lose it.</li>
 *   <li>Saturation, HTTP/S3/Redis/serialization/SSE failures are counted and logged; none of them
 *       change or block a Terraform run.</li>
 * </ul>
 */
@Slf4j
@Service
public class StructuredOutputPersistenceQueue {

    private final Map<Key, StructuredSnapshot> pending = new ConcurrentHashMap<>();
    private final Map<Key, RetainedSnapshot> retained = new ConcurrentHashMap<>();
    private final Semaphore signal = new Semaphore(0);
    private final Object drainLock = new Object();
    private final AtomicLong sequence = new AtomicLong();

    private final int capacity;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final boolean recoveryEnabled;
    private final long recoveryRetentionMs;
    private final long recoveryInitialDelayMs;
    private final long recoveryMaxDelayMs;
    private final int recoveryMaxRetained;
    private final MeterRegistry meterRegistry;
    private final StructuredSnapshotPersister persister;

    private volatile boolean running;
    private volatile boolean persisting;
    private Thread worker;
    private ScheduledExecutorService recoveryScheduler;

    /** One snapshot held for delayed recovery after its normal retry budget was spent. */
    private static final class RetainedSnapshot {
        private volatile StructuredSnapshot snapshot;
        private final long firstRetainedAtEpochMs;
        private volatile long nextAttemptAtEpochMs;
        private volatile long currentDelayMs;

        RetainedSnapshot(StructuredSnapshot snapshot, long now, long initialDelayMs) {
            this.snapshot = snapshot;
            this.firstRetainedAtEpochMs = now;
            this.currentDelayMs = initialDelayMs;
            this.nextAttemptAtEpochMs = now + jitter(initialDelayMs);
        }
    }

    public StructuredOutputPersistenceQueue(StructuredOutputProperties properties,
                                            ExecutorFlagsProperties flags,
                                            MeterRegistry meterRegistry,
                                            // @Lazy breaks the construction cycle: the persister
                                            // depends (transitively) on Plan/ApplyStructuredOutputService,
                                            // which depend on this queue.
                                            @Lazy StructuredSnapshotPersister persister) {
        this.capacity = Math.max(1, properties.getQueueCapacity());
        this.maxAttempts = Math.max(1, properties.getMaxPersistAttempts());
        this.initialBackoffMs = Math.max(0, properties.getInitialBackoffMs());
        this.maxBackoffMs = Math.max(this.initialBackoffMs, properties.getMaxBackoffMs());
        this.recoveryEnabled = flags.isStructuredOutputRecovery();
        this.recoveryRetentionMs = Math.max(0, properties.getRecoveryRetentionMs());
        this.recoveryInitialDelayMs = Math.max(100, properties.getRecoveryInitialDelayMs());
        this.recoveryMaxDelayMs = Math.max(this.recoveryInitialDelayMs, properties.getRecoveryMaxDelayMs());
        this.recoveryMaxRetained = Math.max(1, properties.getRecoveryMaxRetained());
        this.meterRegistry = meterRegistry;
        this.persister = persister;
        Gauge.builder("terrakube.executor.structured.output.queue.depth", pending, Map::size)
                .description("Structured-output snapshots waiting to be persisted")
                .register(meterRegistry);
        Gauge.builder("terrakube.executor.structured.output.recovery.pending", retained, Map::size)
                .description("Structured-output snapshots retained for delayed recovery")
                .register(meterRegistry);
    }

    @PostConstruct
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "structured-output-persistence");
        worker.setDaemon(true);
        worker.start();
        if (recoveryEnabled) {
            recoveryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "structured-output-recovery");
                t.setDaemon(true);
                return t;
            });
            recoveryScheduler.scheduleWithFixedDelay(this::recoverRetained, 5, 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        signal.release();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (recoveryScheduler != null) {
            recoveryScheduler.shutdownNow();
        }
        // Best-effort single pass over anything still retained - bounded, never blocks pod exit
        // beyond the small window below.
        drainRetainedOnce(Duration.ofSeconds(5));
    }

    /** Monotonic sequence for snapshot ordering / eviction. */
    public long nextSequence() {
        return sequence.incrementAndGet();
    }

    /** Record that a snapshot could not be built (serialization failure) - never fails the run. */
    public void dropSerialization() {
        meterRegistry.counter("terrakube.executor.structured.output.dropped", "reason", "serialization").increment();
    }

    /** Non-blocking, never-throwing hand-off. Safe to call from a Terraform reader thread. */
    public void submit(StructuredSnapshot snapshot) {
        try {
            Key key = snapshot.key();
            // A newer snapshot supersedes anything retained for delayed recovery for this key.
            RetainedSnapshot alreadyRetained = retained.get(key);
            if (alreadyRetained != null && snapshot.getSequence() > alreadyRetained.snapshot.getSequence()) {
                alreadyRetained.snapshot = snapshot;
                alreadyRetained.nextAttemptAtEpochMs = System.currentTimeMillis();
            }
            if (!pending.containsKey(key) && pending.size() >= capacity) {
                evictOne(key);
            }
            pending.put(key, snapshot);
            signal.release();
        } catch (Throwable t) {
            log.warn("Dropping structured-output snapshot for job {} step {}: {}",
                    snapshot.getJobId(), snapshot.getStepId(), t.toString());
        }
    }

    /** Blocks up to {@code timeout} for the queue to empty. Returns false on timeout (caller warns). */
    public boolean awaitDrain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (drainLock) {
            while (!pending.isEmpty() || persisting) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    drainLock.wait(Math.min(remainingMs, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    int queueDepth() {
        return pending.size();
    }

    int retainedDepth() {
        return retained.size();
    }

    private void evictOne(Key incoming) {
        Map.Entry<Key, StructuredSnapshot> victim = null;
        int victimRank = Integer.MIN_VALUE;
        for (Map.Entry<Key, StructuredSnapshot> candidate : pending.entrySet()) {
            int rank = evictionRank(candidate, incoming);
            if (victim == null || rank > victimRank
                    || (rank == victimRank && candidate.getValue().getSequence() < victim.getValue().getSequence())) {
                victim = candidate;
                victimRank = rank;
            }
        }
        if (victim != null && pending.remove(victim.getKey(), victim.getValue())) {
            meterRegistry.counter("terrakube.executor.structured.output.dropped", "reason", "capacity").increment();
            log.warn("Structured-output queue full ({}); dropped a {} snapshot for job {} step {}",
                    capacity, victim.getValue().isFinalSnapshot() ? "final" : "progress",
                    victim.getKey().jobId(), victim.getKey().stepId());
        }
    }

    // Higher rank = better eviction candidate: prefer non-final over final, then same job/step.
    private int evictionRank(Map.Entry<Key, StructuredSnapshot> candidate, Key incoming) {
        int rank = 0;
        if (!candidate.getValue().isFinalSnapshot()) {
            rank += 2;
        }
        if (candidate.getKey().jobId().equals(incoming.jobId())
                && candidate.getKey().stepId().equals(incoming.stepId())) {
            rank += 1;
        }
        return rank;
    }

    private void runLoop() {
        while (running) {
            try {
                signal.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            signal.drainPermits();
            drainOnce();
        }
        drainOnce();
    }

    private void drainOnce() {
        for (Key key : new ArrayList<>(pending.keySet())) {
            StructuredSnapshot snapshot = pending.remove(key);
            if (snapshot == null) {
                continue;
            }
            persisting = true;
            try {
                persistWithRetry(snapshot);
            } catch (Throwable t) {
                log.warn("structured-output worker skipped a snapshot for job {} step {}: {}",
                        snapshot.getJobId(), snapshot.getStepId(), t.toString());
            } finally {
                persisting = false;
            }
        }
        synchronized (drainLock) {
            drainLock.notifyAll();
        }
    }

    private void persistWithRetry(StructuredSnapshot snapshot) {
        long start = System.currentTimeMillis();
        String phase = snapshot.getPhase() == StructuredSnapshot.Phase.PLAN ? "plan" : "apply";
        for (int attempt = 0; attempt < maxAttempts && running; attempt++) {
            if (attempt > 0) {
                sleepBackoff(attempt - 1);
                if (!running) {
                    break;
                }
            }
            if (tryPersist(snapshot, phase)) {
                supersedeRetained(snapshot);
                return;
            }
        }
        meterRegistry.counter("terrakube.executor.structured.output.persist", "outcome", "failure").increment();
        meterRegistry.counter("terrakube.executor.structured.output.persist.failures", "phase", phase).increment();
        log.warn("structured-output persist gave up after {} attempts for job {} step {} phase {} ({} ms elapsed)",
                maxAttempts, snapshot.getJobId(), snapshot.getStepId(), phase, System.currentTimeMillis() - start);
        retain(snapshot);
    }

    private boolean tryPersist(StructuredSnapshot snapshot, String phase) {
        try {
            if (persister.persist(snapshot)) {
                meterRegistry.counter("terrakube.executor.structured.output.persist", "outcome", "success").increment();
                return true;
            }
        } catch (Throwable t) {
            log.warn("structured-output persist threw for job {} step {} phase {}: {}",
                    snapshot.getJobId(), snapshot.getStepId(), phase, t.toString());
        }
        return false;
    }

    // Only ever called from the single persistence-worker thread (persistWithRetry), so the
    // size-check-then-put below is not racing another retain().
    private void retain(StructuredSnapshot snapshot) {
        if (!recoveryEnabled) {
            return;
        }
        Key key = snapshot.key();
        RetainedSnapshot existing = retained.get(key);
        if (existing != null) {
            if (snapshot.getSequence() >= existing.snapshot.getSequence()) {
                existing.snapshot = snapshot;
            }
            return;
        }
        if (retained.size() >= recoveryMaxRetained) {
            evictRetained();
        }
        log.info("Retaining {} structured snapshot for job {} step {} phase {} for delayed recovery (ts={})",
                snapshot.isFinalSnapshot() ? "final" : "progress", snapshot.getJobId(), snapshot.getStepId(),
                snapshot.getPhase(), snapshot.getCreatedAtEpochMs());
        retained.put(key, new RetainedSnapshot(snapshot, System.currentTimeMillis(), recoveryInitialDelayMs));
    }

    private void evictRetained() {
        retained.entrySet().stream()
                // prefer evicting non-final (rank 1) over final (rank 0), then the oldest
                .min(Comparator
                        .<Map.Entry<Key, RetainedSnapshot>>comparingInt(e -> e.getValue().snapshot.isFinalSnapshot() ? 1 : 0)
                        .thenComparingLong(e -> e.getValue().firstRetainedAtEpochMs))
                .ifPresent(victim -> {
                    if (retained.remove(victim.getKey(), victim.getValue())) {
                        meterRegistry.counter("terrakube.executor.structured.output.recovery.evictions").increment();
                        log.warn("Recovery retention full ({}); evicted a {} snapshot for job {} step {}",
                                recoveryMaxRetained, victim.getValue().snapshot.isFinalSnapshot() ? "final" : "progress",
                                victim.getKey().jobId(), victim.getKey().stepId());
                    }
                });
    }

    private void supersedeRetained(StructuredSnapshot persisted) {
        RetainedSnapshot existing = retained.get(persisted.key());
        if (existing != null && persisted.getSequence() >= existing.snapshot.getSequence()) {
            retained.remove(persisted.key(), existing);
        }
    }

    // package-private for deterministic testing (production drives it from the scheduled task)
    void recoverRetained() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, RetainedSnapshot> entry : new ArrayList<>(retained.entrySet())) {
            RetainedSnapshot held = entry.getValue();
            if (now < held.nextAttemptAtEpochMs) {
                continue;
            }
            if (now - held.firstRetainedAtEpochMs >= recoveryRetentionMs) {
                if (retained.remove(entry.getKey(), held)) {
                    meterRegistry.counter("terrakube.executor.structured.output.recovery.expired").increment();
                    log.warn("Recovery retention expired for job {} step {} phase {} after {} ms",
                            entry.getKey().jobId(), entry.getKey().stepId(), entry.getKey().phase(),
                            now - held.firstRetainedAtEpochMs);
                }
                continue;
            }
            attemptRecovery(entry.getKey(), held, now);
        }
    }

    private void attemptRecovery(Key key, RetainedSnapshot held, long now) {
        StructuredSnapshot snapshot = held.snapshot;
        String phase = snapshot.getPhase() == StructuredSnapshot.Phase.PLAN ? "plan" : "apply";
        meterRegistry.counter("terrakube.executor.structured.output.recovery.attempts").increment();
        boolean ok;
        try {
            ok = persister.persist(snapshot);
        } catch (Throwable t) {
            ok = false;
            log.warn("Recovery persist threw for job {} step {} phase {}: {}",
                    snapshot.getJobId(), snapshot.getStepId(), phase, t.toString());
        }
        if (ok) {
            retained.remove(key, held);
            log.info("Recovered structured snapshot for job {} step {} phase {} (retained {} ms, ts={})",
                    snapshot.getJobId(), snapshot.getStepId(), phase, now - held.firstRetainedAtEpochMs,
                    snapshot.getCreatedAtEpochMs());
            return;
        }
        held.currentDelayMs = Math.min(recoveryMaxDelayMs, held.currentDelayMs * 2);
        held.nextAttemptAtEpochMs = now + jitter(held.currentDelayMs);
    }

    private void drainRetainedOnce(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        for (Map.Entry<Key, RetainedSnapshot> entry : new ArrayList<>(retained.entrySet())) {
            if (System.nanoTime() >= deadline) {
                return;
            }
            try {
                if (persister.persist(entry.getValue().snapshot)) {
                    retained.remove(entry.getKey(), entry.getValue());
                }
            } catch (Throwable t) {
                log.warn("Shutdown recovery pass failed for job {} step {}: {}",
                        entry.getKey().jobId(), entry.getKey().stepId(), t.toString());
            }
        }
    }

    private void sleepBackoff(int retryIndex) {
        int shift = Math.min(retryIndex, 20);
        long backoff = Math.min(maxBackoffMs, initialBackoffMs << shift);
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long jitter(long delayMs) {
        long span = Math.max(1, delayMs / 4);
        return delayMs + ThreadLocalRandom.current().nextLong(-span, span + 1);
    }
}
