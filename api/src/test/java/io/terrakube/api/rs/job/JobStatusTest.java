package io.terrakube.api.rs.job;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStatusTest {

    @Test
    void onlyUnfinishedStatusesHoldTheQueue() {
        Set<JobStatus> expected = EnumSet.of(JobStatus.pending, JobStatus.waitingApproval, JobStatus.approved,
                JobStatus.queue, JobStatus.running);

        for (JobStatus status : JobStatus.values()) {
            assertEquals(expected.contains(status), status.holdsTheQueue(), status.name());
        }
        assertEquals(expected, JobStatus.holdingTheQueue());
    }

    @Test
    void aTerminalStatusNeverHoldsTheQueue() {
        for (JobStatus status : JobStatus.values()) {
            if (status.isTerminal()) {
                assertFalse(status.holdsTheQueue(), status.name());
            }
        }
        assertTrue(JobStatus.waitingApproval.holdsTheQueue());
    }

    @Test
    void theSetOfStatusesCannotBeChanged() {
        assertThrows(UnsupportedOperationException.class, () -> JobStatus.holdingTheQueue().add(JobStatus.completed));
    }
}
