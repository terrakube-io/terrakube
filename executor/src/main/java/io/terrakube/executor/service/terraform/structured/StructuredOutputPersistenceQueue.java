package io.terrakube.executor.service.terraform.structured;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot.Key;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, coalescing, retrying hand-off between the Terraform/OpenTofu process-output reader
 * threads and job-context persistence.
 *
 * <ul>
 *   <li>{@link #submit} never blocks and never throws - it coalesces by {@code (jobId, stepId,
 *       phase)} so only the newest unsaved snapshot for a step survives, and evicts an older
 *       snapshot (preferring one for the same job/step) when full.</li>
 *   <li>A single worker thread does the GET/merge/POST + SSE via {@link StructuredSnapshotPersister},
 *       retrying with capped exponential backoff.</li>
 *   <li>Saturation, HTTP/S3/Redis/serialization/SSE failures are counted and logged; none of them
 *       change or block a Terraform run.</li>
 * </ul>
 */
@Slf4j
@Service
public class StructuredOutputPersistenceQueue {

    private final Map<Key, StructuredSnapshot> pending = new ConcurrentHashMap<>();
    private final Semaphore signal = new Semaphore(0);
    private final Object drainLock = new Object();
    private final AtomicLong sequence = new AtomicLong();

    private final int capacity;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final MeterRegistry meterRegistry;
    private final StructuredSnapshotPersister persister;

    private volatile boolean running;
    private volatile boolean persisting;
    private Thread worker;

    public StructuredOutputPersistenceQueue(StructuredOutputProperties properties,
                                            MeterRegistry meterRegistry,
                                            // @Lazy breaks the construction cycle: the persister
                                            // depends (transitively) on Plan/ApplyStructuredOutputService,
                                            // which depend on this queue.
                                            @Lazy StructuredSnapshotPersister persister) {
        this.capacity = Math.max(1, properties.getQueueCapacity());
        this.maxAttempts = Math.max(1, properties.getMaxPersistAttempts());
        this.initialBackoffMs = Math.max(0, properties.getInitialBackoffMs());
        this.maxBackoffMs = Math.max(this.initialBackoffMs, properties.getMaxBackoffMs());
        this.meterRegistry = meterRegistry;
        this.persister = persister;
        Gauge.builder("terrakube.executor.structured.output.queue.depth", pending, Map::size)
                .description("Structured-output snapshots waiting to be persisted")
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

    private void evictOne(Key incoming) {
        Map.Entry<Key, StructuredSnapshot> victim = null;
        boolean victimSameJobStep = false;
        for (Map.Entry<Key, StructuredSnapshot> candidate : pending.entrySet()) {
            boolean sameJobStep = candidate.getKey().jobId().equals(incoming.jobId())
                    && candidate.getKey().stepId().equals(incoming.stepId());
            if (victim == null
                    || (sameJobStep && !victimSameJobStep)
                    || (sameJobStep == victimSameJobStep
                        && candidate.getValue().getSequence() < victim.getValue().getSequence())) {
                victim = candidate;
                victimSameJobStep = sameJobStep;
            }
        }
        if (victim != null && pending.remove(victim.getKey(), victim.getValue())) {
            meterRegistry.counter("terrakube.executor.structured.output.dropped", "reason", "capacity").increment();
            log.warn("Structured-output queue full ({}); dropped an older snapshot for job {} step {}",
                    capacity, victim.getKey().jobId(), victim.getKey().stepId());
        }
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
            boolean ok = false;
            try {
                ok = persister.persist(snapshot);
            } catch (Throwable t) {
                log.warn("structured-output persist threw for job {} step {} phase {}: {}",
                        snapshot.getJobId(), snapshot.getStepId(), phase, t.toString());
            }
            if (ok) {
                meterRegistry.counter("terrakube.executor.structured.output.persist", "outcome", "success").increment();
                return;
            }
        }
        meterRegistry.counter("terrakube.executor.structured.output.persist", "outcome", "failure").increment();
        meterRegistry.counter("terrakube.executor.structured.output.persist.failures", "phase", phase).increment();
        log.warn("structured-output persist gave up after {} attempts for job {} step {} phase {} ({} ms elapsed)",
                maxAttempts, snapshot.getJobId(), snapshot.getStepId(), phase, System.currentTimeMillis() - start);
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
}
