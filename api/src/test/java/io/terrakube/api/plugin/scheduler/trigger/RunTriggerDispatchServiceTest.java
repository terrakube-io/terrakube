package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.job.*;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.quartz.ObjectAlreadyExistsException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Dispatcher tests keep flow/state qualification and post-commit scheduling separate from joins. */
class RunTriggerDispatchServiceTest {
    JobRepository jobs = mock(JobRepository.class);
    StepRepository steps = mock(StepRepository.class);
    WorkspaceRunTriggerRepository triggers = mock(WorkspaceRunTriggerRepository.class);
    HistoryRepository history = mock(HistoryRepository.class);
    TclService tcl = mock(TclService.class);
    JobNotificationTrigger notifications = mock(JobNotificationTrigger.class);
    ScheduleJobService scheduler = mock(ScheduleJobService.class);
    RunTriggerProperties properties = new RunTriggerProperties();
    RunCascadeCoordinatorService coordinator = mock(RunCascadeCoordinatorService.class);
    RunTriggerDispatchService service;
    Job parent;
    RunCascade cascade;
    WorkspaceRunTrigger edge;

    @BeforeEach void setup() {
        service = new RunTriggerDispatchService(jobs, steps, triggers, history, tcl, notifications,
                scheduler, properties, mock(RunTriggerDispatchMetrics.class), coordinator);
        Workspace source = new Workspace(); source.setId(UUID.randomUUID());
        parent = new Job(); parent.setId(900); parent.setWorkspace(source); parent.setStatus(JobStatus.completed);
        cascade = new RunCascade(); cascade.setId(UUID.randomUUID());
        edge = new WorkspaceRunTrigger(); edge.setId(UUID.randomUUID()); edge.setDestinationWorkspace(source);
        when(jobs.findById(900)).thenReturn(Optional.of(parent));
        when(coordinator.findCascade(900)).thenReturn(Optional.of(cascade));
        when(coordinator.snapshotTriggers(cascade.getId(), source.getId())).thenReturn(List.of(edge));
    }
    void flow(FlowType type) {
        Step step = new Step(); step.setStepNumber(100); step.setStatus(JobStatus.completed);
        when(steps.findByJobId(900)).thenReturn(List.of(step));
        when(tcl.getFlowTypeForStep(parent, 100)).thenReturn(type.toString());
    }
    @ParameterizedTest @EnumSource(value=FlowType.class, names={"terraformApply","terraformDestroy","customScripts"})
    void qualifiesCompletedStateChangingFlows(FlowType type) {
        flow(type); service.dispatchInternal(900);
        verify(coordinator).enqueue(cascade, edge, parent, 1, type == FlowType.terraformDestroy, true);
        verify(coordinator).resolveNode(parent, true);
    }
    @ParameterizedTest @EnumSource(value=FlowType.class, names={"terraformPlan","terraformPlanDestroy"})
    void resolvesExistingCascadeDependenciesEvenForPlanOnlyJobs(FlowType type) {
        flow(type); service.dispatchInternal(900);
        verify(coordinator).enqueue(cascade, edge, parent, 1, false, false);
    }
    @Test void unchangedStateResolvesDependenciesWithoutDispatching() {
        flow(FlowType.terraformApply);
        History current = new History(); current.setSerial(5); current.setMd5("same"); current.setCreatedDate(new Date());
        History baseline = new History(); baseline.setSerial(5); baseline.setMd5("same");
        when(history.findFirstByWorkspaceAndJobReferenceOrderByCreatedDateDesc(parent.getWorkspace(), "900")).thenReturn(Optional.of(current));
        when(history.findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(parent.getWorkspace(), current.getCreatedDate())).thenReturn(Optional.of(baseline));
        service.dispatchInternal(900);
        verify(coordinator).enqueue(cascade, edge, parent, 1, false, false);
    }
    @Test void schedulesCommittedPendingJobsAndToleratesReplay() throws Exception {
        flow(FlowType.terraformApply);
        Job created = new Job(); created.setId(901); created.setStatus(JobStatus.pending);
        when(coordinator.enqueue(cascade, edge, parent, 1, false, true)).thenReturn(created);
        doThrow(new ObjectAlreadyExistsException("already scheduled")).when(scheduler).createJobContext(created);
        service.dispatchInternal(900);
        verify(scheduler).createJobContext(created);
    }
    @Test void schedulingFailureRetriesTheDurableEventWithoutDroppingOtherDestinations() throws Exception {
        flow(FlowType.terraformApply);
        WorkspaceRunTrigger second = new WorkspaceRunTrigger(); second.setDestinationWorkspace(parent.getWorkspace());
        when(coordinator.snapshotTriggers(any(), any())).thenReturn(List.of(edge, second));
        Job created = new Job(); created.setStatus(JobStatus.pending);
        when(coordinator.enqueue(cascade, edge, parent, 1, false, true)).thenReturn(created);
        doThrow(new RuntimeException("Quartz unavailable")).when(scheduler).createJobContext(created);
        assertThatThrownBy(() -> service.dispatchInternal(900)).isInstanceOf(RunTriggerDispatchPartialFailureException.class);
        verify(coordinator).enqueue(cascade, second, parent, 1, false, true);
    }
    @Test void completedReplayDoesNotScheduleAgain() throws Exception {
        flow(FlowType.terraformApply); Job created = new Job(); created.setStatus(JobStatus.completed);
        when(coordinator.enqueue(any(), any(), any(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(created);
        service.dispatchInternal(900); verifyNoInteractions(scheduler);
    }
    @Test void noChangeIndependentRunDoesNotStartACascade() {
        flow(FlowType.terraformPlan); when(coordinator.findCascade(900)).thenReturn(Optional.empty());
        service.dispatchInternal(900); verify(coordinator, never()).ensureCascade(any());
    }
    @Test void depthLimitResolvesExistingDependenciesAsSkipped() {
        flow(FlowType.terraformApply); parent.setCascadeDepth(properties.getMaxCascadeDepth());
        service.dispatchInternal(900);
        verify(coordinator).enqueue(cascade, edge, parent, properties.getMaxCascadeDepth()+1, false, false);
    }
    @Test void disabledFeatureDoesNoWork() {
        properties.setEnabled(false); service.dispatchInternal(900); verifyNoInteractions(coordinator);
    }
}
