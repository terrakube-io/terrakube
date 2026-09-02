package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobTerminalStateDeriverTest {

    private final JobTerminalStateDeriver deriver = new JobTerminalStateDeriver();

    private Job job(JobStatus status) {
        Job j = new Job();
        j.setStatus(status);
        return j;
    }

    private Step step(JobStatus status) {
        Step s = new Step();
        s.setStatus(status);
        return s;
    }

    @Test
    void approvedJobWithAllStepsCompletedDerivesCompleted() {
        DerivedOutcome outcome = deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.completed)));
        assertThat(outcome).isEqualTo(DerivedOutcome.COMPLETED);
        assertThat(outcome.targetStatus()).contains(JobStatus.completed);
    }

    @Test
    void singleCompletedPlanStepWithNoChangesDerivesCompleted() {
        Job j = job(JobStatus.approved);
        j.setPlanChanges(false);
        assertThat(deriver.derive(j, List.of(step(JobStatus.completed)))).isEqualTo(DerivedOutcome.COMPLETED);
    }

    @Test
    void anyFailedStepDerivesFailedEvenWithLaterCompletedSteps() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.failed))))
                .isEqualTo(DerivedOutcome.FAILED);
    }

    @Test
    void aCancelledStepDerivesCancelled() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.cancelled))))
                .isEqualTo(DerivedOutcome.CANCELLED);
    }

    @Test
    void aRejectedStepOrRejectedJobDerivesRejected() {
        assertThat(deriver.derive(job(JobStatus.approved), List.of(step(JobStatus.rejected))))
                .isEqualTo(DerivedOutcome.REJECTED);
        assertThat(deriver.derive(job(JobStatus.rejected), List.of(step(JobStatus.completed))))
                .isEqualTo(DerivedOutcome.ALREADY_TERMINAL); // rejected is already terminal - rule 1 wins
    }

    @Test
    void alreadyTerminalJobIsLeftAlone() {
        for (JobStatus terminal : List.of(JobStatus.completed, JobStatus.failed, JobStatus.rejected,
                JobStatus.cancelled, JobStatus.noChanges)) {
            assertThat(deriver.derive(job(terminal), List.of(step(JobStatus.completed))))
                    .isEqualTo(DerivedOutcome.ALREADY_TERMINAL);
        }
    }

    @Test
    void waitingApprovalWithZeroPendingStepsIsRetainedNotTransitioned() {
        assertThat(deriver.derive(job(JobStatus.waitingApproval), List.of(step(JobStatus.completed))))
                .isEqualTo(DerivedOutcome.RETAIN_WAITING_APPROVAL);
    }

    @Test
    void noStepsAtAllIsAnAnomaly() {
        assertThat(deriver.derive(job(JobStatus.approved), List.of())).isEqualTo(DerivedOutcome.ANOMALY);
    }

    @Test
    void notExecutedStepsCountAsDoneForCompletion() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.notExecuted))))
                .isEqualTo(DerivedOutcome.COMPLETED);
    }

    @Test
    void anUnexpectedStepStatusMixIsAnAnomaly() {
        // e.g. a 'queue' step left behind with no pending steps and status approved
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.queue))))
                .isEqualTo(DerivedOutcome.ANOMALY);
    }
}
