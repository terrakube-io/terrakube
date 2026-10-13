package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRevisionTest {

    // The counter is JVM-wide and shared with every other test, so only relative properties hold.
    @Test
    void isStrictlyIncreasingAndNeverBehindWallClockMicros() {
        long previous = ContextRevision.next();
        for (int i = 0; i < 10_000; i++) {
            long floor = System.currentTimeMillis() * 1000;
            long next = ContextRevision.next();
            assertTrue(next > previous, "revision went backwards: " + previous + " -> " + next);
            assertTrue(next >= floor, "revision " + next + " is behind wall clock " + floor);
            previous = next;
        }
    }
}
