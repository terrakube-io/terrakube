package io.terrakube.api.plugin.notification;

import io.terrakube.api.plugin.logs.StepOutputReader;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobFailureSummaryServiceTest {

    @Mock
    StepRepository stepRepository;
    @Mock
    StepOutputReader stepOutputReader;

    @InjectMocks
    JobFailureSummaryService subject;

    private Job job() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        Job job = new Job();
        job.setId(721);
        job.setStatus(JobStatus.failed);
        job.setOrganization(organization);
        return job;
    }

    private Step step(int number, JobStatus status) {
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(number);
        step.setStatus(status);
        return step;
    }

    @Test
    void readsTheFailedStepsOutputAndTrimsTheTail() {
        Job job = job();
        Step planStep = step(100, JobStatus.completed);
        Step applyStep = step(200, JobStatus.failed);
        when(stepRepository.findByJobId(721)).thenReturn(List.of(planStep, applyStep));
        String longOutput = "x".repeat(900) + "\nError: creating instance: quota exceeded\n";
        when(stepOutputReader.read(job, applyStep)).thenReturn(longOutput);

        String reason = subject.describeFailure(job);

        assertThat(reason).endsWith("Error: creating instance: quota exceeded");
        assertThat(reason.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void picksTheFailedStepNotTheHighestNumbered() {
        Job job = job();
        Step failed = step(100, JobStatus.failed);
        Step laterCancelled = step(200, JobStatus.cancelled);
        when(stepRepository.findByJobId(721)).thenReturn(List.of(failed, laterCancelled));
        when(stepOutputReader.read(any(), any())).thenReturn("boom");

        subject.describeFailure(job);

        ArgumentCaptor<Step> captor = ArgumentCaptor.forClass(Step.class);
        verify(stepOutputReader).read(eq(job), captor.capture());
        assertThat(captor.getValue()).isSameAs(failed);
    }

    @Test
    void fallsBackToTheLastStepWhenNoStepIsMarkedFailed() {
        Job job = job();
        Step first = step(100, JobStatus.completed);
        Step last = step(200, JobStatus.running);
        when(stepRepository.findByJobId(721)).thenReturn(List.of(first, last));
        when(stepOutputReader.read(job, last)).thenReturn("dispatch error");

        assertThat(subject.describeFailure(job)).isEqualTo("dispatch error");
    }

    @Test
    void returnsNullWhenThereAreNoSteps() {
        when(stepRepository.findByJobId(721)).thenReturn(List.of());

        assertThat(subject.describeFailure(job())).isNull();
    }

    @Test
    void returnsNullWhenTheStepHasNoOutput() {
        Job job = job();
        Step applyStep = step(200, JobStatus.failed);
        when(stepRepository.findByJobId(721)).thenReturn(List.of(applyStep));
        when(stepOutputReader.read(job, applyStep)).thenReturn("   ");

        assertThat(subject.describeFailure(job)).isNull();
    }

    @Test
    void neverThrows() {
        when(stepRepository.findByJobId(721)).thenThrow(new RuntimeException("db down"));

        assertThat(subject.describeFailure(job())).isNull();
    }
}
