package io.terrakube.executor.service.terraform.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputPersistenceQueueTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StructuredSnapshot snap(String step, long seq, String addr) {
        Map<String, Object> row = new HashMap<>();
        row.put("address", addr);
        return StructuredSnapshot.copyOf("o", "1", step, StructuredSnapshot.Phase.PLAN, seq, false,
                List.of(row), List.of(), mapper);
    }

    private StructuredSnapshot finalSnap(String step, long seq, String addr) {
        Map<String, Object> row = new HashMap<>();
        row.put("address", addr);
        return StructuredSnapshot.copyOf("o", "1", step, StructuredSnapshot.Phase.PLAN, seq, true,
                List.of(row), List.of(), mapper);
    }

    private StructuredOutputProperties props(int capacity) {
        StructuredOutputProperties p = new StructuredOutputProperties();
        p.setQueueCapacity(capacity);
        p.setMaxPersistAttempts(2);
        p.setInitialBackoffMs(1);
        p.setMaxBackoffMs(2);
        p.setRecoveryInitialDelayMs(100);
        p.setRecoveryMaxDelayMs(200);
        p.setRecoveryRetentionMs(60_000);
        p.setRecoveryMaxRetained(4);
        return p;
    }

    private io.terrakube.executor.configuration.ExecutorFlagsProperties flags(boolean recovery) {
        io.terrakube.executor.configuration.ExecutorFlagsProperties f =
                new io.terrakube.executor.configuration.ExecutorFlagsProperties();
        f.setStructuredOutputRecovery(recovery);
        return f;
    }

    private StructuredOutputPersistenceQueue started(StructuredSnapshotPersister persister, SimpleMeterRegistry reg, int capacity) {
        return started(persister, reg, capacity, false);
    }

    private StructuredOutputPersistenceQueue started(StructuredSnapshotPersister persister, SimpleMeterRegistry reg,
                                                     int capacity, boolean recovery) {
        StructuredOutputPersistenceQueue queue =
                new StructuredOutputPersistenceQueue(props(capacity), flags(recovery), reg, persister);
        queue.start();
        return queue;
    }

    @Test
    void submitReturnsImmediatelyEvenWhenPersisterBlocks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StructuredSnapshotPersister slow = s -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(slow, new SimpleMeterRegistry(), 256);

        long startNanos = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            queue.submit(snap("step-1", i, "a" + i));
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(elapsedMs < 500, "200 submits blocked for " + elapsedMs + "ms");
        release.countDown();
        queue.stop();
    }

    @Test
    void coalescesRepeatedUpdatesForTheSameKey() throws Exception {
        List<StructuredSnapshot> persisted = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        StructuredSnapshotPersister capture = s -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            persisted.add(s);
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(capture, new SimpleMeterRegistry(), 256);

        for (int i = 0; i < 20; i++) {
            queue.submit(snap("step-1", i, "addr" + i));
        }
        gate.countDown();

        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertTrue(persisted.size() < 20, "expected coalescing, persisted " + persisted.size());
        assertEquals("addr19", persisted.get(persisted.size() - 1).getChanges().get(0).get("address"));
        queue.stop();
    }

    @Test
    void dropsOldestWhenFullAndCountsIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch block = new CountDownLatch(1);
        StructuredSnapshotPersister stuck = s -> {
            try {
                block.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(stuck, registry, 4);

        for (int i = 0; i < 20; i++) {
            queue.submit(snap("step-" + i, i, "a"));
        }

        assertTrue(queue.queueDepth() <= 5, "depth was " + queue.queueDepth());
        assertTrue(registry.get("terrakube.executor.structured.output.dropped").counter().count() >= 10);
        block.countDown();
        queue.stop();
    }

    @Test
    void persistFailureIsCountedAndNeverThrows() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StructuredOutputPersistenceQueue queue = started(s -> false, registry, 64);

        queue.submit(snap("step-1", 1, "a"));

        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertEquals(1.0, registry.get("terrakube.executor.structured.output.persist.failures")
                .tag("phase", "plan").counter().count());
        assertEquals(1.0, registry.get("terrakube.executor.structured.output.persist")
                .tag("outcome", "failure").counter().count());
        queue.stop();
    }

    @Test
    void persisterThrowingIsSwallowedAndRetried() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        int[] calls = {0};
        StructuredSnapshotPersister flaky = s -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new RuntimeException("boom");
            }
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(flaky, registry, 64);

        queue.submit(snap("step-1", 1, "a"));

        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertEquals(2, calls[0]);
        assertEquals(1.0, registry.get("terrakube.executor.structured.output.persist")
                .tag("outcome", "success").counter().count());
        queue.stop();
    }

    // --- delayed recovery -------------------------------------------------------------------

    @Test
    void retainsNewestSnapshotAfterRetryExhaustion() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StructuredOutputPersistenceQueue queue = started(s -> false, registry, 64, true);

        queue.submit(finalSnap("step-1", 1, "a"));

        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertEquals(1, queue.retainedDepth());
        assertEquals(1.0, registry.get("terrakube.executor.structured.output.recovery.pending").gauge().value());
        queue.stop();
    }

    @Test
    void recoverRetainedPersistsOnceStorageRecovers() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<Long> persistedSequences = Collections.synchronizedList(new java.util.ArrayList<>());
        StructuredSnapshotPersister persister = s -> {
            if (!healthy.get()) {
                return false;
            }
            persistedSequences.add(s.getSequence());
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(persister, registry, 64, true);

        queue.submit(snap("step-1", 1, "a"));
        queue.submit(finalSnap("step-1", 2, "b")); // newer supersedes the retained progress snapshot
        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertEquals(1, queue.retainedDepth());

        healthy.set(true);
        Thread.sleep(200); // let the retained entry become due (recovery-initial-delay = 100ms)
        queue.recoverRetained();

        assertEquals(0, queue.retainedDepth());
        assertEquals(List.of(2L), persistedSequences);
        assertTrue(registry.get("terrakube.executor.structured.output.recovery.attempts").counter().count() >= 1);
        queue.stop();
    }

    @Test
    void recoveryRetentionExpiryRemovesTheSnapshotAndCountsIt() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StructuredOutputProperties p = props(64);
        p.setRecoveryRetentionMs(1);
        StructuredOutputPersistenceQueue queue =
                new StructuredOutputPersistenceQueue(p, flags(true), registry, s -> false);
        queue.start();

        queue.submit(finalSnap("step-1", 1, "a"));
        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        Thread.sleep(150);
        queue.recoverRetained();

        assertEquals(0, queue.retainedDepth());
        assertEquals(1.0, registry.get("terrakube.executor.structured.output.recovery.expired").counter().count());
        queue.stop();
    }

    @Test
    void retainedCapacityKeepsFinalSnapshotsOverProgress() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // recoveryMaxRetained = 4 (see props)
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<String> persistedSteps = Collections.synchronizedList(new java.util.ArrayList<>());
        StructuredSnapshotPersister persister = s -> {
            if (!healthy.get()) {
                return false;
            }
            persistedSteps.add(s.getStepId());
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(persister, registry, 64, true);

        queue.submit(finalSnap("final-step", 1, "a"));
        for (int i = 0; i < 8; i++) {
            queue.submit(snap("progress-" + i, 10 + i, "a"));
        }
        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));

        assertEquals(4, queue.retainedDepth());
        assertTrue(registry.get("terrakube.executor.structured.output.recovery.evictions").counter().count() >= 4);

        healthy.set(true);
        Thread.sleep(200);
        queue.recoverRetained();
        assertTrue(persistedSteps.contains("final-step"), "final snapshot must survive eviction: " + persistedSteps);
        queue.stop();
    }

    @Test
    void pendingCapacityEvictsProgressBeforeFinal() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch block = new CountDownLatch(1);
        List<String> persistedSteps = Collections.synchronizedList(new java.util.ArrayList<>());
        StructuredSnapshotPersister persister = s -> {
            try {
                block.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            persistedSteps.add(s.getStepId());
            return true;
        };
        StructuredOutputPersistenceQueue queue = started(persister, registry, 2, false);

        queue.submit(finalSnap("final-step", 1, "a")); // worker picks this up and blocks on it
        Thread.sleep(50);
        queue.submit(snap("progress-a", 2, "a"));
        queue.submit(snap("progress-b", 3, "a"));
        queue.submit(snap("progress-c", 4, "a")); // forces an eviction among the pending progress rows

        block.countDown();
        assertTrue(queue.awaitDrain(Duration.ofSeconds(5)));
        assertTrue(persistedSteps.contains("final-step"), "final snapshot must not be evicted: " + persistedSteps);
        queue.stop();
    }
}
