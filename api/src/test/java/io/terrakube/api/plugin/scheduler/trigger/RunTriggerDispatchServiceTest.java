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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RunTriggerDispatchServiceTest {

    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final int COMPLETED_JOB_ID = 900;

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRunTriggerRepository triggerRepository;
    TclService tclService;
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

        // Assigning an id on save mirrors the database and lets the assertions distinguish
        // the jobs created for each dependent.
        lenient().doAnswer(i -> {
            Job saved = i.getArgument(0);
            saved.setId(nextJobId++);
            return saved;
        }).when(jobRepository).save(any(Job.class));

        subject = new RunTriggerDispatchService(jobRepository, stepRepository, triggerRepository,
                tclService, jobNotificationTrigger, scheduleJobService, properties);
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
        verify(jobRepository).save(captor.capture());
        Job created = captor.getValue();

        assertThat(created.getWorkspace()).isSameAs(destination);
        assertThat(created.getOrganization()).isSameAs(destination.getOrganization());
        assertThat(created.getTemplateReference()).isEqualTo("template-default");
        assertThat(created.getStatus()).isEqualTo(JobStatus.pending);
        assertThat(created.getVia()).isEqualTo(JobVia.RUN_TRIGGER.getValue());
        assertThat(created.getTriggeredByJobId()).isEqualTo(COMPLETED_JOB_ID);
        assertThat(created.getCascadeDepth()).isEqualTo(1);
        assertThat(created.isPlanChanges()).isTrue();
        assertThat(created.isRefresh()).isTrue();
        assertThat(created.isRefreshOnly()).isFalse();

        // Neither hook fires for a repository save, so both have to happen explicitly.
        verify(jobNotificationTrigger).notifyStatusChanged(created);
        verify(scheduleJobService).createJobContext(created);
    }

    @Test
    void destroyDispatches() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformDestroy, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository).save(any(Job.class));
    }

    @Test
    void customScriptsDispatches() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.customScripts, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository).save(any(Job.class));
    }

    /** A run that only planned leaves nothing downstream to react to. */
    @Test
    void planOnlyRunDispatchesNothing() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformPlan, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository, never()).save(any(Job.class));
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

        verify(jobRepository, never()).save(any(Job.class));
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

        verify(jobRepository, never()).save(any(Job.class));
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void cascadeStopsAtTheConfiguredDepth() {
        properties.setMaxCascadeDepth(3);
        Job completed = completedJob(3);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void cascadeDispatchesOnTheStepBelowTheLimit() {
        properties.setMaxCascadeDepth(3);
        Job completed = completedJob(2);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", "template-default"), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getCascadeDepth()).isEqualTo(3);
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
        ArgumentCaptor<Job> first = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(3)).save(first.capture());
        List<String> firstRun = first.getAllValues().stream().map(j -> j.getWorkspace().getName()).toList();

        // Same graph, same apply: the cap must not pick a different arbitrary subset.
        List<WorkspaceRunTrigger> shuffled = new ArrayList<>(List.of(triggers));
        java.util.Collections.reverse(shuffled);
        doReturn(shuffled).when(triggerRepository).findEnabledBySourceWorkspaceId(SOURCE_ID);

        subject.dispatchFor(COMPLETED_JOB_ID);
        ArgumentCaptor<Job> second = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(6)).save(second.capture());
        List<String> secondRun = second.getAllValues().subList(3, 6).stream()
                .map(j -> j.getWorkspace().getName()).toList();

        assertThat(secondRun).containsExactlyInAnyOrderElementsOf(firstRun);
    }

    @Test
    void killSwitchDispatchesNothing() {
        properties.setEnabled(false);
        completedJob(0);

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository, never()).save(any(Job.class));
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

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getTemplateReference()).isEqualTo(template.getId().toString());
    }

    @Test
    void dependentWithNoTemplateAtAllIsSkippedWithoutThrowing() {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);
        triggersFromSource(trigger(workspace("consumer", null), null));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(jobRepository, never()).save(any(Job.class));
    }

    // ---------------------------------------------------------------- fault isolation

    /**
     * The reason each dependent is wrapped: without it, the first workspace that cannot be
     * scheduled would cost every dependent listed after it.
     */
    @Test
    void oneFailingDependentDoesNotStopTheOthers() throws Exception {
        Job completed = completedJob(0);
        stepsWithFlow(completed, FlowType.terraformApply, JobStatus.completed);

        Workspace broken = workspace("broken", "template-default");
        Workspace healthy = workspace("healthy", "template-default");
        // Ordered so the failure is not the last one processed.
        broken.setId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        healthy.setId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
        triggersFromSource(trigger(broken, null), trigger(healthy, null));

        doThrow(new IllegalStateException("scheduler down"))
                .when(scheduleJobService).createJobContext(argThatTargets(broken));

        subject.dispatchFor(COMPLETED_JOB_ID);

        verify(scheduleJobService).createJobContext(argThatTargets(healthy));
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

        verify(jobRepository, never()).save(any(Job.class));
    }

    private static Job argThatTargets(Workspace workspace) {
        return org.mockito.ArgumentMatchers.argThat(job -> job != null
                && job.getWorkspace() != null
                && job.getWorkspace().getId().equals(workspace.getId()));
    }
}
