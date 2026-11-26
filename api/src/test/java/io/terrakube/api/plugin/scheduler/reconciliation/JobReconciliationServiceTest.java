package io.terrakube.api.plugin.scheduler.reconciliation;

import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.cost.InfracostContextExtractor;
import io.terrakube.api.plugin.cost.WorkspaceCostTrackingService;
import io.terrakube.api.plugin.cost.WorkspaceCostWriter;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventWriter;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobReconciliationServiceTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    JobNotificationTrigger jobNotificationTrigger;
    ScheduleJobService scheduleJobService;
    Scheduler scheduler;
    RunTriggerEventWriter runTriggerEventWriter;
    WorkspaceCostTrackingService workspaceCostTrackingService;
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
        lenient().doReturn(Optional.empty()).when(jobRepository).findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(any(), anyInt(), any());
        lenient().doReturn(Optional.empty()).when(jobRepository).findFirstByWorkspaceOrderByIdDesc(any());
        runTriggerEventWriter = mock(RunTriggerEventWriter.class);
        workspaceCostTrackingService = mock(WorkspaceCostTrackingService.class);
        subject = new JobReconciliationService(jobRepository, stepRepository, new WorkspaceJobStatusService(jobRepository, workspaceRepository),
                new JobTerminalStateDeriver(), jobNotificationTrigger, scheduleJobService,
                scheduler, new JobReconciliationMetrics(new SimpleMeterRegistry()),
                runTriggerEventWriter, workspaceCostTrackingService);
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

    /**
     * Anchoring the event write to the transition, not to the job status, is what makes it fire
     * once: a second caller finds the job already terminal and returns before this point.
     */
    @Test
    void transitionToCompletedEnqueuesARunTriggerEventOnce() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        subject.reconcile(755, false);

        verify(runTriggerEventWriter, times(1)).enqueueIfQualifying(j);
    }

    @Test
    void transitionToFailedDispatchesNoRunTriggers() {
        Job j = job(756, JobStatus.running);
        doReturn(j).when(jobRepository).lockForUpdate(756);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.failed, 200)))
                .when(stepRepository).findByJobId(756);

        ReconciliationResult result = subject.reconcile(756, false);

        assertThat(result.targetStatus()).isEqualTo(JobStatus.failed);
        verify(runTriggerEventWriter, never()).enqueueIfQualifying(any());
    }

    @Test
    void alreadyTerminalDispatchesNoRunTriggers() {
        Job j = job(755, JobStatus.completed);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        subject.reconcile(755, false);

        verify(runTriggerEventWriter, never()).enqueueIfQualifying(any());
    }

    @Test
    void dryRunDispatchesNoRunTriggers() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        subject.reconcile(755, true);

        verify(runTriggerEventWriter, never()).enqueueIfQualifying(any());
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

    @Test
    void transitionToCompletedUpdatesWorkspaceCostViaTrackingService() {
        WorkspaceCostTrackingService costTrackingService = mock(WorkspaceCostTrackingService.class);
        JobReconciliationService costAwareSubject = new JobReconciliationService(
                jobRepository,
                stepRepository,
                new WorkspaceJobStatusService(jobRepository, workspaceRepository),
                new JobTerminalStateDeriver(),
                jobNotificationTrigger,
                scheduleJobService,
                scheduler,
                new JobReconciliationMetrics(new SimpleMeterRegistry()),
                runTriggerEventWriter,
                costTrackingService);

        Job j = job(758, JobStatus.approved);
        List<Step> steps = List.of(step(JobStatus.completed, 100), step(JobStatus.completed, 200));
        doReturn(j).when(jobRepository).lockForUpdate(758);
        doReturn(steps).when(stepRepository).findByJobId(758);

        ReconciliationResult result = costAwareSubject.reconcile(758, false);

        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        verify(costTrackingService, times(1)).updateWorkspaceCostOnCompletion(j, steps);
    }

    @Test
    void reconcileCompletesEvenWhenInfracostHasOutOfRangeTotalAndLongCurrency() {
        StorageTypeService storage = mock(StorageTypeService.class);
        TclService tclService = mock(TclService.class);
        WorkspaceCostWriter writer = new WorkspaceCostWriter(workspaceRepository);
        WorkspaceCostTrackingService realCostService = new WorkspaceCostTrackingService(
                writer, tclService, new InfracostContextExtractor(storage, new ObjectMapper()));

        JobReconciliationService costAwareSubject = new JobReconciliationService(
                jobRepository,
                stepRepository,
                new WorkspaceJobStatusService(jobRepository, workspaceRepository),
                new JobTerminalStateDeriver(),
                jobNotificationTrigger,
                scheduleJobService,
                scheduler,
                new JobReconciliationMetrics(new SimpleMeterRegistry()),
                runTriggerEventWriter,
                realCostService);

        Job j = job(759, JobStatus.approved);
        j.getWorkspace().setId(UUID.randomUUID());
        j.setTcl("ZmxvdzoK");
        List<Step> steps = List.of(step(JobStatus.completed, 100), step(JobStatus.completed, 200));
        doReturn(j).when(jobRepository).lockForUpdate(759);
        doReturn(steps).when(stepRepository).findByJobId(759);
        when(tclService.getFlowTypeForStep(j, 100)).thenReturn(FlowType.terraformPlan.name());
        when(tclService.getFlowTypeForStep(j, 200)).thenReturn(FlowType.terraformApply.name());
        when(storage.getContext(759)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "OVERLY_LONG_CURRENCY_CODE",
                    "totalMonthlyCost": 999999999999999999.00
                  }
                }
                """);

        ReconciliationResult result = costAwareSubject.reconcile(759, false);

        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.completed);
        verify(runTriggerEventWriter, times(1)).enqueueIfQualifying(j);
        verify(workspaceRepository, never()).updateCost(any(), any(), any(), any());

        // Even if updateCost throws during a valid destroy, reconcile must still complete.
        doThrow(new RuntimeException("DB timeout")).when(workspaceRepository).updateCost(any(), any(), any(), any());
        when(tclService.getFlowTypeForStep(j, 100)).thenReturn(FlowType.terraformPlanDestroy.name());
        Job j2 = job(760, JobStatus.approved);
        j2.setWorkspace(j.getWorkspace());
        j2.setTcl("ZmxvdzoK");
        doReturn(j2).when(jobRepository).lockForUpdate(760);
        doReturn(steps).when(stepRepository).findByJobId(760);
        when(tclService.getFlowTypeForStep(j2, 100)).thenReturn(FlowType.terraformPlanDestroy.name());
        when(tclService.getFlowTypeForStep(j2, 200)).thenReturn(FlowType.terraformApply.name());

        ReconciliationResult destroyResult = costAwareSubject.reconcile(760, false);

        assertThat(destroyResult.targetStatus()).isEqualTo(JobStatus.completed);
        verify(runTriggerEventWriter, times(1)).enqueueIfQualifying(j2);
    }
}
