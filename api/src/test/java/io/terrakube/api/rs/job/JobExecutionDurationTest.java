package io.terrakube.api.rs.job;

import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobExecutionDurationTest {
    @Test
    void olderRunsAndUnstartedPhasesRemainUnknown() {
        Job job = new Job();
        assertNull(job.getPlanDurationMs());
        job.setStep(List.of(new Step()));
        assertNull(job.getPlanDurationMs());
        assertNull(job.getApplyDurationMs());
    }

    @Test
    void sumsRecordedCommandsByPhaseNotNameOrStatus() {
        Job job = new Job();
        Step plan = step("plan", 30_000L, JobStatus.completed);
        plan.setName("Custom preview");
        Step failedApply = step("apply", 120_000L, JobStatus.failed);
        Step approval = step(null, null, JobStatus.completed);
        approval.setName("Approve Plan");
        job.setStep(List.of(plan, failedApply, approval, step("plan", 0L, JobStatus.completed)));
        assertEquals(30_000L, job.getPlanDurationMs());
        assertEquals(120_000L, job.getApplyDurationMs());
    }

    private Step step(String phase, Long milliseconds, JobStatus status) {
        Step step = new Step();
        step.setExecutionPhase(phase);
        step.setExecutionDurationMs(milliseconds);
        step.setStatus(status);
        return step;
    }
}
