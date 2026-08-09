package io.terrakube.executor.service.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorCapacityGateTest {

    @Test
    void firstAcquireSucceedsSecondFailsUntilReleased() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();

        assertTrue(gate.tryAcquire());
        assertFalse(gate.tryAcquire());

        gate.release();

        assertTrue(gate.tryAcquire());
    }

    @Test
    void releaseWithoutAPriorAcquireIsSafe() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();

        gate.release();

        assertTrue(gate.tryAcquire());
    }
}
