package io.terrakube.api.plugin.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;

import graphql.Assert;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorUnavailableException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Command;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.scheduler.job.tcl.model.ScheduleTemplate;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.variable.IncompleteVariableException;
import io.terrakube.api.plugin.variable.InvalidVariableCategoryException;
import io.terrakube.api.plugin.variable.WorkspaceVariableValidationService;
import io.terrakube.api.plugin.vcs.PrCommentService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.ScheduleRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TemplateRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
public class ScheduleJobTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    TclService tclService;
    ExecutorService executorService;
    WorkspaceRepository workspaceRepository;
    SoftDeleteService softDeleteService;
    ScheduleJobService scheduleJobService;
    GitHubWebhookService gitHubWebhookService;
    PrCommentService prCommentService;
    ScheduleRepository scheduleRepository;
    TemplateRepository templateRepository;
    EphemeralExecutorService ephemeralExecutorService;
    GitLabWebhookService gitLabWebhookService;
    GlobalVarRepository globalVarRepository;
    VariableRepository variableRepository;
    WorkspaceVariableValidationService workspaceVariableValidationService;
    RedisTemplate<String, Object> redisTemplate;
    ValueOperations<String, Object> valueOperations;
    JobNotificationTrigger jobNotificationTrigger;

    UUID stepId = UUID.randomUUID();

    @BeforeEach
    public void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        tclService = mock(TclService.class, new FailUnkownMethod<TclService>());
        executorService = mock(ExecutorService.class, new FailUnkownMethod<ExecutorService>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        softDeleteService = mock(SoftDeleteService.class, new FailUnkownMethod<SoftDeleteService>());
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        gitHubWebhookService = mock(GitHubWebhookService.class, new FailUnkownMethod<GitHubWebhookService>());
        prCommentService = mock(PrCommentService.class, new FailUnkownMethod<PrCommentService>());
        scheduleRepository = mock(ScheduleRepository.class, new FailUnkownMethod<ScheduleRepository>());
        templateRepository = mock(TemplateRepository.class, new FailUnkownMethod<TemplateRepository>());
        gitLabWebhookService = mock(GitLabWebhookService.class, new FailUnkownMethod<GitLabWebhookService>());
        globalVarRepository = mock(GlobalVarRepository.class, new FailUnkownMethod<GlobalVarRepository>());
        variableRepository = mock(VariableRepository.class, new FailUnkownMethod<VariableRepository>());
        workspaceVariableValidationService = mock(
                WorkspaceVariableValidationService.class,
                new FailUnkownMethod<WorkspaceVariableValidationService>());
        lenient().doNothing().when(workspaceVariableValidationService).validateWorkspaceVariables(any());
        lenient().doReturn(Optional.empty()).when(prCommentService).extractRunSummary(any());

        redisTemplate = mock(RedisTemplate.class, new FailUnkownMethod<RedisTemplate>());
        valueOperations = mock(ValueOperations.class, new FailUnkownMethod<ValueOperations>());
        // Plain mock (not FailUnkownMethod): almost every status-transition path under test now
        // calls notifyStatusChanged(), and its outcome is irrelevant to what these tests assert.
        jobNotificationTrigger = mock(JobNotificationTrigger.class);
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any(Duration.class));
        lenient().doReturn(true).when(redisTemplate).delete(anyString());

        // Default to "next in line, no one to wake" so non-FIFO tests behave as before; the
        // ordering tests below override these.
        lenient().doReturn(true).when(jobRepository).isJobNextInDispatchOrder(anyInt());
        lenient().doReturn(null).when(jobRepository).findNextDispatchableJobId();
    }

    private ScheduleJob subject() {
        return new ScheduleJob(
                scheduleRepository,
                templateRepository,
                gitLabWebhookService,
                jobRepository,
                stepRepository,
                tclService,
                executorService,
                workspaceRepository,
                softDeleteService,
                scheduleJobService,
                redisTemplate,
                gitHubWebhookService,
                null,
                prCommentService,
                globalVarRepository,
                variableRepository,
                workspaceVariableValidationService,
                jobNotificationTrigger);
    }

    private Job job(JobStatus status) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);

        Organization org = new Organization();
        org.setName("ze-org");

        Workspace workspace = new Workspace();
        workspace.setLocked(false);
        workspace.setVcs(vcs);

        Step step = new Step();
        step.setId(stepId);
        step.setStatus(JobStatus.pending);

        Job job = new Job();
        job.setId(4711);
        job.setStatus(status);
        job.setCreatedDate(new Date(System.currentTimeMillis()));
        job.setVia("GitLab");
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setPlanChanges(true);
        job.setStep(Collections.singletonList(step));

        return job;
    }

    @Test
    public void expiredJobsAreDescheduled() {
        Job job = job(JobStatus.pending);
        job.setCreatedDate(DateUtils.addDays(new Date(System.currentTimeMillis()), -1));

        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown, null);
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
    }

    @Test
    public void expiredJobsAreDescheduledEvenIfVcsIntegrationFails() {
        Job job = job(JobStatus.pending);
        job.setCreatedDate(DateUtils.addDays(new Date(System.currentTimeMillis()), -1));

        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doThrow(new RuntimeException("Boom!")).when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.failed, job.getStatus());
    }

    @Test
    public void pendingJobWithPlanChanges() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        verify(jobRepository, times(1)).save(job);
        verify(valueOperations, times(1)).setIfAbsent(any(), any(), any(Duration.class));
        verify(redisTemplate, times(1)).delete(anyString());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
        // Regression check: the scheduler updates job.status via a plain jobRepository.save(),
        // never through Elide, so JobNotificationHook (an Elide LifeCycleHook) never sees this
        // transition - notifyStatusChanged() must be called explicitly at every such call site.
        verify(jobNotificationTrigger, times(1)).notifyStatusChanged(job);
    }

    @Test
    public void pendingJobFailsOnExecutionChanges() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep().get(0)).when(stepRepository).getReferenceById(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doThrow(new ExecutionException(new Exception("Boom!"))).when(executorService).execute(any(), any(), any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertTrue(subject().runExecution(job));

        verify(jobRepository, times(1)).save(job);
        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown, null);
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
    }

    @Test
    public void pendingJobFailsClearlyWithWorkspaceAndKeyWhenVariableHasNoCategory() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doThrow(new InvalidVariableCategoryException("LEGACY_VAR has no category"))
                .when(workspaceVariableValidationService).validateWorkspaceVariables(any());
        doReturn("Run blocked because this workspace has variables with no category (must be TERRAFORM or ENV).\n- LEGACY_VAR")
                .when(workspaceVariableValidationService).buildInvalidCategoryMessage(any(Workspace.class));
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getStep().get(0)).when(stepRepository).getReferenceById(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        // A malformed legacy variable must fail the job outright, not leave it pending/retrying.
        Assertions.assertTrue(subject().runExecution(job));

        verify(executorService, never()).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
        Assertions.assertEquals(WorkspaceVariableValidationService.INVALID_CATEGORY_STEP_NAME, job.getStep().get(0).getName());
        Assertions.assertTrue(job.getOutput().contains("LEGACY_VAR"));
    }

    @Test
    public void pendingJobFailsWhenWorkspaceVariablesAreIncomplete() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doThrow(new IncompleteVariableException("TF_API_TOKEN is incomplete"))
                .when(workspaceVariableValidationService).validateWorkspaceVariables(any());
        doReturn("Run blocked because this workspace still has incomplete sensitive variables.\n- TF_API_TOKEN")
                .when(workspaceVariableValidationService).buildIncompleteVariableMessage(any(Workspace.class));
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getStep().get(0)).when(stepRepository).getReferenceById(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        verify(executorService, never()).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(WorkspaceVariableValidationService.INCOMPLETE_VARIABLE_STEP_NAME, job.getStep().get(0).getName());
        Assertions.assertTrue(job.getOutput().contains("TF_API_TOKEN"));
    }

    @Test
    public void pendingJobRetriesWhenExecutorUnavailable() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doThrow(new ExecutorUnavailableException("no ready executor")).when(executorService).execute(any(), any(), any());

        // No free executor should leave the job in place for the next Quartz retry
        // (JOB_CONTEXT_INTERVAL), not fail it outright.
        Assert.assertFalse(subject().runExecution(job));

        verify(jobRepository, times(0)).save(any());
        verify(stepRepository, times(0)).save(any());
        verify(gitLabWebhookService, times(0)).sendCommitStatus(any(), any(), any());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void pendingJobDefersWithoutCallingExecutorWhenNotNextInDispatchOrder() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(false).when(jobRepository).isJobNextInDispatchOrder(job.getId());

        // An older job elsewhere is still waiting for the pool - this job must not even attempt
        // dispatch, so an older job never loses a race to a newer one.
        Assert.assertFalse(subject().runExecution(job));

        verify(executorService, times(0)).execute(any(), any(), any());
        verify(jobRepository, times(0)).save(any());
        verify(stepRepository, times(0)).save(any());
        verify(gitLabWebhookService, times(0)).sendCommitStatus(any(), any(), any());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void successfulPendingJobDispatchWakesTheNextDispatchableJobImmediately() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        Job nextJob = job(JobStatus.pending);
        nextJob.setId(job.getId() + 1);

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());
        doReturn(nextJob.getId()).when(jobRepository).findNextDispatchableJobId();
        doReturn(nextJob).when(jobRepository).getReferenceById(nextJob.getId());
        doNothing().when(scheduleJobService).createJobContextNow(nextJob);

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        verify(scheduleJobService, times(1)).createJobContextNow(nextJob);
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void jobIsSkippedWhenAnotherWorkerAlreadyHoldsItsExecutionLock() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        // Simulate an overlapping Quartz firing for this same job id (e.g. the 30s recurring
        // trigger racing a wake-up one-shot) already holding the execution lock. Nothing about
        // the job's flow is stubbed beyond this, since runExecution must bail out before touching
        // tclService/jobRepository/executorService at all - not just before dispatch.
        doReturn(false).when(valueOperations).setIfAbsent(any(), any(), any(Duration.class));

        Assert.assertFalse(subject().runExecution(job));

        verify(tclService, times(0)).initJobConfiguration(any());
        verify(executorService, times(0)).execute(any(), any(), any());
        verify(jobRepository, times(0)).save(any());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void jobIsSkippedWhenRedisIsUnreachable() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        // A Redis outage must fail closed (skip this run, retry later) rather than run
        // unprotected - a duplicate/overlapping run is worse than a delayed one.
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).setIfAbsent(any(), any(), any(Duration.class));

        Assert.assertFalse(subject().runExecution(job));

        verify(tclService, times(0)).initJobConfiguration(any());
        verify(executorService, times(0)).execute(any(), any(), any());
        verify(jobRepository, times(0)).save(any());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void pendingJobWithApprovalFlow() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.approval.name());
        flow.setTeam("ze-team");

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());

        Assert.assertTrue(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.waitingApproval, job.getStatus());
        Assertions.assertEquals("ze-team", job.getApprovalTeam());
    }

    @Test
    public void pendingJobWithAutoApplyFlow() throws Exception {
        Job job = job(JobStatus.pending);
        job.setAutoApply(true);

        Flow flow = new Flow();
        flow.setType(FlowType.approval.name());
        flow.setTeam("ze-team");

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
        Assertions.assertEquals("", job.getApprovalTeam());
    }

    @Test
    public void pendingJobWithDisableWorkspace() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.disableWorkspace.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(softDeleteService).disableWorkspaceSchedules(any());

        Assert.assertTrue(subject().runExecution(job));

        verify(softDeleteService, times(1)).disableWorkspaceSchedules(job.getWorkspace());
        Assertions.assertEquals(JobStatus.completed, job.getStatus());
        Assertions.assertEquals(true, job.getWorkspace().isDeleted());
    }

    @Test
    public void pendingJobWithScheduleTemplate() throws Exception {
        Job job = job(JobStatus.pending);

        UUID tId = UUID.randomUUID();
        Template template = new Template();
        template.setId(tId);
        template.setName("ze-template");

        ScheduleTemplate schedTemplate = new ScheduleTemplate();
        schedTemplate.setName(template.getName());
        schedTemplate.setSchedule("0 * * * *");

        Flow flow = new Flow();
        flow.setType(FlowType.scheduleTemplates.name());
        flow.setTemplates(Collections.singletonList(schedTemplate));

        UUID sId = UUID.randomUUID();

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep().get(0)).when(stepRepository).getReferenceById(any());
        doReturn(null).when(stepRepository).save(any());
        doReturn(template).when(templateRepository).getByOrganizationNameAndName(any(), any());
        doAnswer(input -> {
            Schedule s = input.getArgument(0);
            s.setId(sId);
            return s;
        }).when(scheduleRepository).save(any());
        doNothing().when(scheduleJobService).createJobTrigger(any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(scheduleJobService, times(1)).createJobTrigger(schedTemplate.getSchedule(), sId.toString());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
        Assertions.assertEquals(JobStatus.completed, job.getStep().get(0).getStatus());
    }

    @Test
    public void pendingJobReferencingUnknownTemplate() throws Exception {
        Job job = job(JobStatus.pending);

        ScheduleTemplate schedTemplate = new ScheduleTemplate();
        schedTemplate.setName("deleted-template");
        schedTemplate.setSchedule("0 * * * *");

        Flow flow = new Flow();
        flow.setType(FlowType.scheduleTemplates.name());
        flow.setTemplates(Collections.singletonList(schedTemplate));

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(null).when(templateRepository).getByOrganizationNameAndName(any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
    }

    @Test
    public void pendingJobWithBrokenTemplate() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.yamlError.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown, null);
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
    }

    @Test
    public void pendingJobWithNoMoreSteps() {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        // Called twice :(
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(null).when(tclService).getNextFlow(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());

        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertTrue(subject().runExecution(job));

        verify(jobRepository, times(1)).save(job);
        verify(workspaceRepository, times(2)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.completed, null);
        Assertions.assertEquals(JobStatus.completed, job.getStatus());
        verify(jobNotificationTrigger, times(1)).notifyStatusChanged(job);
    }

    @Test
    public void approvedJob() throws Exception {
        Job job = job(JobStatus.approved);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        verify(jobRepository, times(2)).save(job);
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void approvedJobFailsOnExecutionError() throws Exception {
        Job job = job(JobStatus.approved);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep().get(0)).when(stepRepository).getReferenceById(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doThrow(new ExecutionException(new Exception("Boom!"))).when(executorService).execute(any(), any(), any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        // TODO Could be true with no extra scheduling, because we know we are done
        Assert.assertTrue(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown, null);
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
    }

    @Test
    public void approvedJobRetriesWhenExecutorUnavailable() throws Exception {
        Job job = job(JobStatus.approved);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doThrow(new ExecutorUnavailableException("no ready executor")).when(executorService).execute(any(), any(), any());

        // No free executor should leave the job in place for the next Quartz retry
        // (JOB_CONTEXT_INTERVAL), not fail it outright.
        Assert.assertFalse(subject().runExecution(job));

        verify(stepRepository, times(0)).save(any());
        verify(gitLabWebhookService, times(0)).sendCommitStatus(any(), any(), any());
        Assertions.assertEquals(JobStatus.approved, job.getStatus());
    }

     @Test
     public void completedJobWithHistoryGloballyVar() throws Exception {
         Job job = job(JobStatus.completed);
         Job prev1 = job(JobStatus.completed);
         prev1.setId(4710);
         Job prev2 = job(JobStatus.completed);
         prev2.setId(4709);

         Globalvar globalVar = new Globalvar();
         globalVar.setKey("KEEP_JOB_HISTORY");
         globalVar.setCategory(Category.ENV);
         globalVar.setValue("1");

         doReturn(Collections.singletonList(globalVar)).when(globalVarRepository).findByOrganization(any());
         doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

         doReturn(false).when(tclService).isTemplatePlanOnly(any());
         doReturn(Optional.of(Collections.emptyList()))
                 .when(jobRepository)
                 .findByWorkspaceAndStatusNotInAndIdLessThan(
                         any(Workspace.class),
                         anyList(),
                         anyInt());
         doReturn(Optional.of(List.of(prev1, prev2)))
                 .when(jobRepository)
                 .findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(
                         any(Workspace.class),
                         anyList(),
                         anyInt());
         doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
         doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
         doNothing().when(jobRepository).delete(any());
          // Passed directly to other mock, so list does not matter
         doReturn(Collections.emptyList()).when(stepRepository).findByJobId(anyInt());
         doNothing().when(stepRepository).deleteAll(anyList());

         doNothing().when(scheduleJobService).deleteJobContext(anyInt());

         Assert.assertTrue(subject().runExecution(job));
         verify(scheduleJobService, times(1)).deleteJobContext(prev2.getId());

         verify(jobRepository, times(1)).delete(any()); // Ensure we do not delete anything else
         verify(jobRepository, times(1)).delete(prev2);
     }

    @Test
    public void completedJobWithHistoryWorkspaceVar() throws Exception {
        Job job = job(JobStatus.completed);
        Job prev1 = job(JobStatus.completed);
        prev1.setId(4710);
        Job prev2 = job(JobStatus.completed);
        prev2.setId(4709);

        Variable variable = new Variable();
        variable.setKey("KEEP_JOB_HISTORY");
        variable.setCategory(Category.ENV);
        variable.setValue("1");

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.singletonList(variable))).when(variableRepository).findByWorkspace(any());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(Optional.of(List.of(prev1, prev2)))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
        doNothing().when(jobRepository).delete(any());
        // Passed directly to other mock, so list does not matter
        doReturn(Collections.emptyList()).when(stepRepository).findByJobId(anyInt());
        doNothing().when(stepRepository).deleteAll(anyList());

        doNothing().when(scheduleJobService).deleteJobContext(anyInt());

        Assert.assertTrue(subject().runExecution(job));
        verify(scheduleJobService, times(1)).deleteJobContext(prev2.getId());

        verify(jobRepository, times(1)).delete(any()); // Ensure we do not delete anything else
        verify(jobRepository, times(1)).delete(prev2);
    }

    @Test
    public void completedJobWithHistorySoftDelete() throws Exception {
        Job job = job(JobStatus.completed);
        Job prev1 = job(JobStatus.completed);
        prev1.setId(4710);
        Job prev2 = job(JobStatus.completed);
        prev2.setId(4709);

        Globalvar keepHistory = new Globalvar();
        keepHistory.setKey("KEEP_JOB_HISTORY");
        keepHistory.setCategory(Category.ENV);
        keepHistory.setValue("1");

        Globalvar softDelete = new Globalvar();
        softDelete.setKey("KEEP_JOB_HISTORY_SOFT_DELETE");
        softDelete.setCategory(Category.ENV);
        softDelete.setValue("true");

        doReturn(List.of(keepHistory, softDelete)).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(Optional.of(List.of(prev1, prev2)))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(Collections.emptyList()).when(stepRepository).findByJobId(anyInt());

        doNothing().when(scheduleJobService).deleteJobContext(anyInt());

        Assert.assertTrue(subject().runExecution(job));
        verify(scheduleJobService, times(1)).deleteJobContext(prev2.getId());

        // prev2 should be soft deleted (flagged), never hard deleted
        verify(jobRepository, never()).delete(any());
        verify(jobRepository, times(1)).save(prev2);
        Assertions.assertTrue(prev2.isDeleted());
        Assertions.assertFalse(prev1.isDeleted());
    }

    @Test
    public void orphanedTriggerSelfDeschedules() throws Exception {
        // The Job row was deleted after this trigger was scheduled (e.g. KEEP_JOB_HISTORY
        // pruning winning the race against the job's own terminal-status cleanup tick, or a
        // soft delete hiding the row via the entity's @SQLRestriction), so execute()'s
        // findById() comes back empty. The fire must remove the orphaned Quartz context
        // instead of failing on every refire forever.
        JobDetail jobDetail = mock(JobDetail.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ScheduleJob.JOB_ID, 4711);
        dataMap.put("isTriggerFromStatusChange", "false");
        Scheduler scheduler = mock(Scheduler.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        doReturn(jobDetail).when(context).getJobDetail();
        doReturn(dataMap).when(jobDetail).getJobDataMap();
        doReturn(scheduler).when(context).getScheduler();
        doReturn(true).when(scheduler).deleteJob(any(JobKey.class));

        doReturn(Optional.empty()).when(jobRepository).findById(4711);

        Assertions.assertDoesNotThrow(() -> subject().execute(context));

        verify(scheduler, times(1)).deleteJob(new JobKey(ScheduleJobService.PREFIX_JOB_CONTEXT + 4711));
    }

    @Test
    public void completedJobIncludesRunSummaryInCommitStatus() {
        Job job = job(JobStatus.completed);
        doReturn(Optional.of("Plan: 2 to add, 0 to change, 1 to destroy."))
                .when(prCommentService).extractRunSummary(job);
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        subject().updateJobStatusOnVcs(job, JobStatus.completed);

        verify(gitLabWebhookService, times(1))
                .sendCommitStatus(job, JobStatus.completed, "Plan: 2 to add, 0 to change, 1 to destroy.");
    }

    @Test
    public void completedJob() {
        Job job = job(JobStatus.completed);

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.completed, null);
        Assertions.assertEquals(JobStatus.notExecuted, job.getStep().get(0).getStatus());
    }

    @Test
    public void executeReleasesTheExecutionLockOnlyAfterProcessingCompletes() throws Exception {
        // Reproduces (and now guards against a different way of reintroducing) a race a live run
        // once exposed: two overlapping firings for the same job both read it as "pending" and
        // both ran completeJob(). The original root cause was the execution lock releasing (inside
        // execute()) before a single surrounding @Transactional commit had actually finished, so a
        // second firing could acquire the freed lock and read pre-commit (stale) state. execute()
        // no longer wraps doRunExecution in one transaction at all (see the class comment on
        // ScheduleJob) - every write it makes (jobRepository.save, workspaceRepository.save, etc.)
        // is its own already-committed call by the time it returns, via Spring Data's own
        // per-repository-method transaction. What this test proves instead: the lock is still held
        // across the entire operation - acquired before job loading, released only after every
        // write doRunExecution makes has already happened - so there's no window for a second
        // firing to see a state this firing hasn't finished writing yet.
        Job job = job(JobStatus.completed);
        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(any(Workspace.class), anyList(), anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
        doReturn(Optional.of(job)).when(jobRepository).findById(job.getId());
        lenient().doReturn(true).when(redisTemplate).delete(anyString());

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(ScheduleJob.JOB_ID, job.getId());
        jobDataMap.put("isTriggerFromStatusChange", "false");
        JobDetail jobDetail = mock(JobDetail.class, new FailUnkownMethod<JobDetail>());
        doReturn(jobDataMap).when(jobDetail).getJobDataMap();
        Scheduler quartzScheduler = mock(Scheduler.class, new FailUnkownMethod<Scheduler>());
        doReturn(true).when(quartzScheduler).deleteJob(any());
        JobExecutionContext jobExecutionContext = mock(JobExecutionContext.class, new FailUnkownMethod<JobExecutionContext>());
        doReturn(jobDetail).when(jobExecutionContext).getJobDetail();
        doReturn(quartzScheduler).when(jobExecutionContext).getScheduler();
        doReturn("InstanceId").when(jobExecutionContext).getFireInstanceId();

        InOrder inOrder = inOrder(valueOperations, jobRepository, workspaceRepository, redisTemplate);

        subject().execute(jobExecutionContext);

        inOrder.verify(valueOperations).setIfAbsent(any(), any(), any(Duration.class)); // lock acquired
        inOrder.verify(jobRepository).findById(job.getId());                            // job loaded
        inOrder.verify(workspaceRepository).save(job.getWorkspace());                   // a write happens
        inOrder.verify(redisTemplate).delete("job-execution-lock:" + job.getId());      // lock released last
    }

    @Test
    public void failedJob() {
        Job job = job(JobStatus.failed);

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());

        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.failed, null);
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
    }

    @Test
    public void nonActionableStatusJob() {
        Job job = job(JobStatus.queue);

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertFalse(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void bypassQueueJob_bypassesWaitingApproval() throws Exception {
        Job job = job(JobStatus.pending);
        job.setTemplateReference("plan-only-template");

        Job previousJob = job(JobStatus.waitingApproval);
        previousJob.setId(4710);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(true).when(tclService).isTemplatePlanOnly("plan-only-template");
        doReturn(false).when(tclService).isCliTemplate("plan-only-template");
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void bypassQueueJob_waitsForActiveApply() {
        Job job = job(JobStatus.pending);
        job.setTemplateReference("plan-only-template");

        Job runningJob = job(JobStatus.running);
        runningJob.setId(4710);
        runningJob.setTcl(java.util.Base64.getEncoder().encodeToString(
            "flow:\n  - type: terraformApply\n    step: 100".getBytes()));

        Step runningStep = new Step();
        runningStep.setId(UUID.randomUUID());
        runningStep.setStatus(JobStatus.running);
        runningStep.setStepNumber(100);

        doReturn(true).when(tclService).isTemplatePlanOnly("plan-only-template");
        doReturn(false).when(tclService).isCliTemplate("plan-only-template");
        doReturn(Optional.of(Collections.singletonList(runningJob)))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(Collections.singletonList(runningStep))
                .when(stepRepository)
                .findByJobId(runningJob.getId());
        doReturn(FlowType.terraformApply.name())
                .when(tclService)
                .getFlowTypeForStep(any(Job.class), anyInt());

        Assert.assertFalse(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void bypassQueueJob_proceedsWhenPreviousJobRunningPlan() throws Exception {
        Job job = job(JobStatus.pending);
        job.setTemplateReference("plan-only-template");

        Job runningJob = job(JobStatus.running);
        runningJob.setId(4710);
        runningJob.setTcl(java.util.Base64.getEncoder().encodeToString(
            "flow:\n  - type: terraformPlan\n    step: 100".getBytes()));

        Step runningStep = new Step();
        runningStep.setId(UUID.randomUUID());
        runningStep.setStatus(JobStatus.running);
        runningStep.setStepNumber(100);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(true).when(tclService).isTemplatePlanOnly("plan-only-template");
        doReturn(false).when(tclService).isCliTemplate("plan-only-template");
        doReturn(Optional.of(Collections.singletonList(runningJob)))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(Collections.singletonList(runningStep))
                .when(stepRepository)
                .findByJobId(runningJob.getId());
        doReturn(FlowType.terraformPlan.name())
                .when(tclService)
                .getFlowTypeForStep(any(Job.class), anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void nonBypassQueueJob_usesNormalQueueLogic() {
        Job job = job(JobStatus.pending);
        job.setTemplateReference("normal-template");

        Job previousJob = job(JobStatus.waitingApproval);
        previousJob.setId(4710);

        doReturn(false).when(tclService).isTemplatePlanOnly("normal-template");
        doReturn(Optional.of(Collections.singletonList(previousJob)))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());

        Assert.assertFalse(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void lockedWorkspaceBlocksUnrelatedJob() {
        Job job = job(JobStatus.pending);
        job.getWorkspace().setLocked(true);
        job.getWorkspace().setLockDescription("Locked by PR #99 apply");

        Assert.assertFalse(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void lockedWorkspaceAllowsItsOwnPrApplyJobToProceed() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPrNumber(4);
        job.setAutoApply(true);
        job.getWorkspace().setLocked(true);
        job.getWorkspace().setLockDescription(WebhookService.buildPrApplyLockDescription(4));

        Flow flow = new Flow();
        flow.setType(FlowType.terraformApply.name());

        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job).when(tclService).initJobConfiguration(any(Job.class));
        doReturn(flow).when(tclService).getNextFlow(any());
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.queue, job.getStatus());
    }

    @Test
    public void completedPrApplyJobUnlocksItsOwnWorkspaceLock() {
        Job job = job(JobStatus.completed);
        job.setPrNumber(4);
        job.setAutoApply(true);
        job.getWorkspace().setLocked(true);
        job.getWorkspace().setLockDescription(WebhookService.buildPrApplyLockDescription(4));

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        // Deliberately stubbed true: postPrCommentIfNeeded must route on job.isAutoApply(), not on
        // whether the workspace's default template happens to look plan-only to tclService, or an
        // apply-via-PR-comment job silently reuses/updates the existing plan comment instead of
        // posting its own apply result (the bug this test guards against).
        doReturn(true).when(tclService).isTemplatePlanOnly(any());
        doReturn(false).when(tclService).isCliTemplate(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
        doNothing().when(prCommentService).postApplyResult(any());
        doNothing().when(prCommentService).acknowledgeCompletion(any());

        Assert.assertTrue(subject().runExecution(job));

        verify(prCommentService, times(1)).postApplyResult(job);
        verify(prCommentService, never()).postPlanResult(any());
        verify(prCommentService, times(1)).acknowledgeCompletion(job);
        Assertions.assertFalse(job.getWorkspace().isLocked());
        Assertions.assertNull(job.getWorkspace().getLockDescription());
    }

    @Test
    public void completedNonAutoApplyPrJobPostsPlanResult() {
        Job job = job(JobStatus.completed);
        job.setPrNumber(4);
        job.setAutoApply(false);

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        // Deliberately stubbed false: a plan-triggered PR job must still post a plan result even if
        // tclService considers the template not plan-only, since the routing no longer consults it.
        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
        doNothing().when(prCommentService).postPlanResult(any());
        doNothing().when(prCommentService).acknowledgeCompletion(any());

        Assert.assertTrue(subject().runExecution(job));

        verify(prCommentService, times(1)).postPlanResult(job);
        verify(prCommentService, never()).postApplyResult(any());
    }

    private void stubRejectedTeardown(Job job) {
        doReturn(false).when(tclService).isTemplatePlanOnly(any());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any(), any());
    }

    private Flow approvalFlowWithOnReject() {
        Command onRejectCommand = new Command();
        onRejectCommand.setRuntime("BASH");
        onRejectCommand.setPriority(100);
        onRejectCommand.setAfter(true);
        onRejectCommand.setScript("echo rejected");

        Flow flow = new Flow();
        flow.setType(FlowType.approval.name());
        flow.setStep(150);
        flow.setTeam("TERRAKUBE_ADMIN");
        flow.setOnReject(List.of(onRejectCommand));
        return flow;
    }

    @Test
    public void rejectedJobExecutesOnRejectCommands() throws Exception {
        Job job = job(JobStatus.rejected);
        Flow flow = approvalFlowWithOnReject();

        stubRejectedTeardown(job);
        doReturn(flow).when(tclService).getNextFlow(job);
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(job);
        doNothing().when(executorService).execute(any(), any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        ArgumentCaptor<Flow> flowCaptor = ArgumentCaptor.forClass(Flow.class);
        verify(executorService, times(1)).execute(any(), any(), flowCaptor.capture());
        Assertions.assertEquals(flow.getOnReject(), flowCaptor.getValue().getCommands());
        Assertions.assertEquals(JobStatus.rejected, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.failed, null);
    }

    @Test
    public void rejectedJobWithoutOnRejectDoesNotCallExecutor() throws Exception {
        Job job = job(JobStatus.rejected);
        Flow flow = approvalFlowWithOnReject();
        flow.setOnReject(null);

        stubRejectedTeardown(job);
        doReturn(flow).when(tclService).getNextFlow(job);

        Assertions.assertTrue(subject().runExecution(job));

        verify(executorService, never()).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.rejected, job.getStatus());
    }

    @Test
    public void rejectedJobTeardownSurvivesOnRejectDispatchFailure() throws Exception {
        Job job = job(JobStatus.rejected);
        Flow flow = approvalFlowWithOnReject();

        stubRejectedTeardown(job);
        doReturn(flow).when(tclService).getNextFlow(job);
        doReturn(stepId.toString()).when(tclService).getCurrentStepId(job);
        doThrow(new ExecutionException(new Exception("Boom!"))).when(executorService).execute(any(), any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.rejected, job.getStatus());
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.failed, null);
    }
}