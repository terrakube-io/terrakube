package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRevisionTest {

    private static final long EPOCH = 1_760_371_234_567L;

    // The counters are JVM-wide, so every test uses its own job id.
    private static String jobId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void isStrictlyIncreasingWithinAnExecution() {
        String job = jobId();
        ContextRevision.begin(job, EPOCH);
        long previous = ContextRevision.next(job);
        assertEquals(EPOCH * ContextRevision.SCALE + 1, previous);
        for (int i = 0; i < 10_000; i++) {
            long next = ContextRevision.next(job);
            assertTrue(next > previous, "revision went backwards: " + previous + " -> " + next);
            previous = next;
        }
        ContextRevision.end(job, EPOCH);
    }

    @Test
    void aHigherEpochJumpsAheadAndALowerOneNeverGoesBack() {
        String job = jobId();
        ContextRevision.begin(job, EPOCH);
        long first = ContextRevision.next(job);

        ContextRevision.begin(job, EPOCH + 1);
        long newer = ContextRevision.next(job);
        assertEquals((EPOCH + 1) * ContextRevision.SCALE + 1, newer);

        ContextRevision.begin(job, EPOCH);
        long afterOlder = ContextRevision.next(job);
        assertTrue(first < newer && newer < afterOlder);
        ContextRevision.end(job, EPOCH + 1);
    }

    @Test
    void aReDispatchedExecutionOutranksEveryRevisionOfThePreviousOne() {
        String job = jobId();
        ContextRevision.begin(job, EPOCH);
        long old = 0;
        for (int i = 0; i < 1000; i++) {
            old = ContextRevision.next(job);
        }
        ContextRevision.end(job, EPOCH);

        ContextRevision.begin(job, EPOCH + 1);
        assertTrue(ContextRevision.next(job) > old);
        ContextRevision.end(job, EPOCH + 1);
    }

    @Test
    void jobsAreIndependent() {
        String a = jobId();
        String b = jobId();
        ContextRevision.begin(a, EPOCH);
        ContextRevision.begin(b, EPOCH);
        ContextRevision.next(a);
        ContextRevision.next(a);

        assertEquals(EPOCH * ContextRevision.SCALE + 1, ContextRevision.next(b));
        ContextRevision.end(a, EPOCH);
        ContextRevision.end(b, EPOCH);
    }

    @Test
    void withoutABeginOrAnEpochTheExecutorClockIsTheEpoch() {
        String job = jobId();
        long floor = System.currentTimeMillis() * ContextRevision.SCALE;
        assertTrue(ContextRevision.next(job) > floor);
        ContextRevision.end(job, 0);

        floor = System.currentTimeMillis() * ContextRevision.SCALE;
        ContextRevision.begin(job, 0);
        assertTrue(ContextRevision.next(job) > floor);
        ContextRevision.end(job, 0);
    }

    @Test
    void aNullJobIdNeverThrowsSoJobSetupAndTeardownCannot() {
        long floor = System.currentTimeMillis() * ContextRevision.SCALE;
        assertDoesNotThrow(() -> {
            ContextRevision.begin(null, EPOCH);
            ContextRevision.end(null, EPOCH);
        });
        assertTrue(ContextRevision.next(null) >= floor);
    }

    @Test
    void endingAnOlderExecutionKeepsTheCounterOfANewerOne() {
        String job = jobId();
        ContextRevision.begin(job, EPOCH);
        ContextRevision.next(job);
        ContextRevision.begin(job, EPOCH + 1);
        ContextRevision.next(job);

        ContextRevision.end(job, EPOCH);
        assertEquals((EPOCH + 1) * ContextRevision.SCALE + 2, ContextRevision.next(job));

        // Its own end removes it: a later write falls back to this executor's clock.
        ContextRevision.end(job, EPOCH + 1);
        long floor = System.currentTimeMillis() * ContextRevision.SCALE;
        assertTrue(ContextRevision.next(job) > floor);
        ContextRevision.end(job, 0);
    }
}
