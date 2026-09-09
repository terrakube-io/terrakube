package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RunTriggerDispatchServiceTest {

    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final int COMPLETED_JOB_ID = 900;

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRunTriggerRepository triggerRepository;
    TclService tclService;
    RunTriggerJobWriter jobWriter;
    JobNotificationTrigger jobNotificationTrigger;
    ScheduleJobService scheduleJobService;
    RunTriggerProperties properties;
    RunTriggerDispatchService subject;

    private int nextJobId;

    @BeforeEach
    void setup() throws Exception {
        jobRepository = mock(JobRepository.class);
        stepRepository = mock(StepRepository.class);
        triggerRepository = mock(WorkspaceRunTriggerRepository.class);
        tclService = mock(TclService.class);
        jobNotificationTrigger = mock(JobNotificationTrigger.class);
        scheduleJobService = mock(ScheduleJobService.class);
        properties = new RunTriggerProperties();
        nextJobId = 1000;

        // The writer owns the shape of the job and its transaction boundary, both covered by
        // RunTriggerJobWriterTest. Here it stands in for a committed row, so the assertions can
        // stay on what the dispatcher decides: who gets one, with which template, at what depth.
        jobWriter = mock(RunTriggerJobWriter.class);
        lenient().doAnswer(i -> {
            Job saved = new Job();
            saved.setId(nextJobId++);
            saved.setWorkspace(i.getArgument(0));
            return saved;
        }).when(jobWriter).persist(any(), any(), any(), anyInt());

        subject = new RunTriggerDispatchService(jobRepository, stepRepository, triggerRepository,
                tclService, jobWriter, jobNotificationTrigger, scheduleJobService, properties);
    }

    // ---------------------------------------------------------------- fixtures

    private Workspace workspace(String name, String defaultTemplate) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("simple");

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setDefaultTemplate(defaultTemplate);
        workspace.setOrganization(organization);
        return workspace;
    }

    private Job completedJob(int cascadeDepth) {
        Workspace source = workspace("source", null);
        source.setId(SOURCE_ID);

        Job job = new Job();
        job.setId(COMPLETED_JOB_ID);
        job.setWorkspace(source);
        job.setStatus(JobStatus.completed);
        job.setCascadeDepth(cascadeDepth);
        doReturn(Optional.of(job)).when(jobRepository).findById(COMPLETED_JOB_ID);
        return job;
    }

    /** One completed step whose flow type is what the test wants to exercise. */
    private void stepsWithFlow(Job job, FlowType flowType, JobStatus stepStatus) {
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(200);
        step.setStatus(stepStatus);
        doReturn(List.of(step)).when(stepRepository).findByJobId(job.getId());
        lenient().doReturn(flowType.toString()).when(tclService).getFlowTypeForStep(job, 200);
    }

    private WorkspaceRunTrigger trigger(Workspace destination, Template template) {
        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
        trigger.setId(UUID.randomUUID());
        trigger.setDestinationWorkspace(destination);
        trigger.setTemplate(template);
        trigger.setEnabled(true);
        return trigger;
    }

    private void triggersFromSource(WorkspaceRunTrigger... triggers) {
        doReturn(new ArrayList<>(List.of(triggers)))
                .when(triggerRepository).findEnabledBySourceWorkspaceId(SOURCE_ID);
    }

    // ---------------------------------------------------------------- qualification

    @Test
    void applyDispatchesToTheDependent() throws Exception {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        Workspace destination = workspace("consumer", "template-default");
        triggersFromSource(trigger(destination, null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobWriter).persist(eq(destination), eq("template-default"), eq(completed), eq(1));
        verify(jobNotificationTrigger).notifyStatusChanged(captor.capture());
        Job created = captor.getValue();

        // Neither Elide hook fires for a repository save, so both side effects are explicit.
        verify(scheduleJobService).createJobContext(created);

        // The order is the fix for the Quartz race: createJobContext fires the trigger at once,
        // and the worker reads on another connection, so the row has to be committed first.
        InOrder ordered = inOrder(jobWriter, scheduleJobService);
        ordered.verify(jobWriter).persist(any(), any(), any(), anyInt());
        ordered.verify(scheduleJobService).createJobContext(created);
    }

    @Test
    void destroyDispatches() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformDestroy, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter).persist(any(), any(), any(), anyInt());
    }

    @Test
    void customScriptsDispatches() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.customScripts, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter).persist(any(), any(), any(), anyInt());
    }

    /** A run that only planned leaves nothing downstream to react to. */
    @Test
    void planOnlyRunDispatchesNothing() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformPlan, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    /**
     * A plan/apply template whose apply never executed still finishes as completed. Reading
     * the step status rather than the job status is what keeps it from firing.
     */
    @Test
    void applyStepThatDidNotRunDispatchesNothing() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.notExecuted);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    @Test
    void unparseableTclDispatchesNothing() {
        Job completed = completedJob(0);
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(200);
        step.setStatus(JobStatus.completed);
        doReturn(List.of(step)).when(stepRepository).findByJobId(COMPLETED_JOB_ID);
        doReturn(null).when(tclService).getFlowTypeForStep(completed, 200);

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void cascadeStopsAtTheConfiguredDepth() {
        properties.setMaxCascadeDepth(3);
        Job completed = completedJob(3);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    @Test
    void cascadeDispatchesOnTheStepBelowTheLimit() {
        properties.setMaxCascadeDepth(3);
        Job completed = completedJob(2);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter).persist(any(), any(), any(), eq(3));
    }

    @Test
    void fanOutIsCappedAndTheSurvivingSubsetIsStable() {
        properties.setMaxDependentsPerApply(3);
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);

        WorkspaceRunTrigger[] triggers = IntStream.range(0, 5)
                .mapToObj(i -> trigger(workspace("consumer-" + i, "template-default"), null))
                .toArray(WorkspaceRunTrigger[]::new);
        triggersFromSource(triggers);

        subject.dispatchFor(COMPLETED_JOB_ID);
        ArgumentCaptor<Workspace> first = ArgumentCaptor.forClass(Workspace.class);
        verify(jobWriter, times(3)).persist(first.capture(), any(), any(), anyInt());
        List<String> firstRun = first.getAllValues().stream().map(Workspace::getName).toList();

        // Same graph, same apply: the cap must not pick a different arbitrary subset.
        List<WorkspaceRunTrigger> shuffled = new ArrayList<>(List.of(triggers));
        java.util.Collections.reverse(shuffled);
        doReturn(shuffled).when(triggerRepository).findEnabledBySourceWorkspaceId(SOURCE_ID);

        subject.dispatchFor(COMPLETED_JOB_ID);
        ArgumentCaptor<Workspace> second = ArgumentCaptor.forClass(Workspace.class);
        verify(jobWriter, times(6)).persist(second.capture(), any(), any(), anyInt());
        List<String> secondRun = second.getAllValues().subList(3, 6).stream()
                .map(Workspace::getName).toList();

        assertThat(secondRun).containsExactlyInAnyOrderElementsOf(firstRun);
    }

    @Test
    void killSwitchDispatchesNothing() {
        properties.setEnabled(false);
        completedJob(0);

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
        verify(stepRepository, never()).findByJobId(anyInt());
    }

    // ---------------------------------------------------------------- template resolution

    @Test
    void triggerTemplateWinsOverTheWorkspaceDefault() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);

        Template template = new Template();
        template.setId(UUID.randomUUID());
        triggersFromSource(trigger(workspace("consumer", "template-default"), template));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter).persist(any(), eq(template.getId().toString()), any(), anyInt());
    }

    @Test
    void dependentWithNoTemplateAtAllIsSkippedWithoutThrowing() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", null), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    // ---------------------------------------------------------------- fault isolation

    /**
     * The reason each dependent is wrapped: without it, the first workspace that cannot be
     * scheduled would cost every dependent listed after it.
     */
    /**
     * The case the per-dependent transaction exists for. A failed insert marks its transaction
     * rollback-only and catching the exception does not clear that, so a shared transaction
     * would take every job created beside it down at commit - through the try/catch meant to
     * prevent exactly that. Only a boundary per dependent makes this assertion true.
     */
    @Test
    void oneFailingInsertDoesNotStopTheOthers() throws Exception {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);

        Workspace broken = brokenFirst();
        Workspace healthy = healthySecond();
        triggersFromSource(trigger(broken, null), trigger(healthy, null));

        doThrow(new DataIntegrityViolationException("constraint violation"))
                .when(jobWriter).persist(eq(broken), any(), any(), anyInt());

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter).persist(eq(healthy), any(), any(), anyInt());
        verify(scheduleJobService).createJobContext(argThatTargets(healthy));
    }

    /** The same guarantee for a failure on the scheduling side rather than the insert. */
    @Test
    void oneFailingScheduleDoesNotStopTheOthers() throws Exception {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);

        Workspace broken = brokenFirst();
        Workspace healthy = healthySecond();
        triggersFromSource(trigger(broken, null), trigger(healthy, null));

        doThrow(new IllegalStateException("scheduler down"))
                .when(scheduleJobService).createJobContext(argThatTargets(broken));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(scheduleJobService).createJobContext(argThatTargets(healthy));
    }

    /** Ids fix the processing order, so the failure is never the last one handled. */
    private Workspace brokenFirst() {
        Workspace broken = workspace("broken", "template-default");
        broken.setId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        return broken;
    }

    private Workspace healthySecond() {
        Workspace healthy = workspace("healthy", "template-default");
        healthy.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        return healthy;
    }

    /** Dispatch must never surface an error on a run that already succeeded. */
    @Test
    void dispatchNeverThrows() {
        completedJob(0);
        doThrow(new IllegalStateException("database gone")).when(stepRepository).findByJobId(anyInt());

        subject.dispatchFor(COMPLETED_JOB_ID);
    }

    @Test
    void unknownJobIsIgnored() {
        doReturn(Optional.empty()).when(jobRepository).findById(COMPLETED_JOB_ID);

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobWriter, never()).persist(any(), any(), any(), anyInt());
    }

    private static Job argThatTargets(Workspace workspace) {
        return org.mockito.ArgumentMatchers.argThat(job -> job != null
                && job.getWorkspace() != null
                && job.getWorkspace().getId().equals(workspace.getId()));
    }
}
