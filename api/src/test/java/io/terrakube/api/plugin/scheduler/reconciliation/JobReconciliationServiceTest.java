package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JobReconciliationServiceTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    JobNotificationTrigger jobNotificationTrigger;
    ScheduleJobService scheduleJobService;
    Scheduler scheduler;
    JobReconciliationService subject;

    @BeforeEach
    void setup() throws Exception {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        jobNotificationTrigger = mock(JobNotificationTrigger.class);
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        scheduler = mock(Scheduler.class, new FailUnkownMethod<Scheduler>());
        lenient().doReturn(true).when(scheduler).deleteJob(any());
        lenient().doAnswer(i -> i.getArgument(0)).when(jobRepository).save(any());
        lenient().doAnswer(i -> i.getArgument(0)).when(workspaceRepository).save(any());
        lenient().doReturn(null).when(jobRepository).findNextDispatchableExecutableJobId();
        subject = new JobReconciliationService(jobRepository, stepRepository, workspaceRepository,
                new JobTerminalStateDeriver(), jobNotificationTrigger, scheduleJobService,
                scheduler, new JobReconciliationMetrics(new SimpleMeterRegistry()));
    }

    private Job job(int id, JobStatus status) {
        Job j = new Job();
        j.setId(id);
        j.setStatus(status);
        j.setWorkspace(new Workspace());
        return j;
    }

    private Step step(JobStatus status, int number) {
        Step s = new Step();
        s.setId(UUID.randomUUID());
        s.setStepNumber(number);
        s.setStatus(status);
        return s;
    }

    @Test
    void approvedWithAllStepsCompletedTransitionsToCompletedOnce() throws Exception {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.completed, 200)))
                .when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.APPLIED);
        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.completed);
        verify(jobRepository).save(j);
        verify(jobNotificationTrigger, times(1)).notifyStatusChanged(j);
        verify(workspaceRepository, times(1)).save(j.getWorkspace());
        verify(scheduler, times(1)).deleteJob(any());
    }

    @Test
    void alreadyTerminalIsANoOpWithNoEvent() throws Exception {
        Job j = job(755, JobStatus.completed);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.ALREADY_TERMINAL);
        verify(jobNotificationTrigger, never()).notifyStatusChanged(any());
        verify(scheduler, never()).deleteJob(any());
    }

    @Test
    void dryRunDoesNotTransition() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, true);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.DRY_RUN);
        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.approved);
        verify(jobRepository, never()).save(any());
        verify(jobNotificationTrigger, never()).notifyStatusChanged(any());
    }

    @Test
    void pendingStepsFoundAfterLockMeansSkippedHasWork() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.pending, 200))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.SKIPPED_HAS_WORK);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void runningOrQueueStepsFoundAfterLockMeansSkippedHasWork() {
        Job j = job(755, JobStatus.running);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.running, 200)))
                .when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.SKIPPED_HAS_WORK);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void anomalyIsHeldNotTransitioned() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.<Step>of()).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.HELD_ANOMALY);
        assertThat(j.getStatus()).isEqualTo(JobStatus.approved);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void failedStepTransitionsToFailedNeverCompleted() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.failed, 200)))
                .when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.targetStatus()).isEqualTo(JobStatus.failed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.failed);
    }
}
