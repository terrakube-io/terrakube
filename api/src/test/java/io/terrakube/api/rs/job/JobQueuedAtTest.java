package io.terrakube.api.rs.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Date;

import org.junit.jupiter.api.Test;

class JobQueuedAtTest {

    @Test
    void stampsTheQueueTimeOnFirstPersist() {
        Job job = new Job();

        job.stampQueuedAt();

        assertNotNull(job.getQueuedAt());
    }

    @Test
    void keepsAnExistingQueueTime() {
        Job job = new Job();
        Date earlier = new Date(1_000L);
        job.setQueuedAt(earlier);

        job.stampQueuedAt();

        assertEquals(earlier, job.getQueuedAt());
    }
}
