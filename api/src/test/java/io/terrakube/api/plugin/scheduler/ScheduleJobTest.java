package io.terrakube.api.plugin.scheduler;

import graphql.Assert;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.scheduler.job.tcl.model.ScheduleTemplate;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.*;
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
import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailUnkownMethod<T> implements Answer<T> {
    @Override
    public T answer(InvocationOnMock invocation) throws Throwable {
        throw new UnsupportedOperationException("Unimplemented method " + invocation.getMethod());
    }
}

@ExtendWith(MockitoExtension.class)
public class ScheduleJobTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    TclService tclService;
    ExecutorService executorService;
    WorkspaceRepository workspaceRepository;
    SoftDeleteService softDeleteService;
    ScheduleJobService scheduleJobService;
    RedisTemplate<String, String> redisTemplate;
    GitHubWebhookService gitHubWebhookService;
    ScheduleRepository scheduleRepository;
    TemplateRepository templateRepository;
    EphemeralExecutorService ephemeralExecutorService;
    GitLabWebhookService gitLabWebhookService;
    GlobalVarRepository globalVarRepository;
    VariableRepository variableRepository;

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
        redisTemplate = mock(RedisTemplate.class, new FailUnkownMethod<RedisTemplate<String, String>>());
        gitHubWebhookService = mock(GitHubWebhookService.class, new FailUnkownMethod<GitHubWebhookService>());
        scheduleRepository = mock(ScheduleRepository.class, new FailUnkownMethod<ScheduleRepository>());
        templateRepository = mock(TemplateRepository.class, new FailUnkownMethod<TemplateRepository>());
        gitLabWebhookService = mock(GitLabWebhookService.class, new FailUnkownMethod<GitLabWebhookService>());
        globalVarRepository = mock(GlobalVarRepository.class, new FailUnkownMethod<GlobalVarRepository>());
        variableRepository = mock(VariableRepository.class, new FailUnkownMethod<VariableRepository>());
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
                globalVarRepository,
                variableRepository);
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

        doReturn(null).when(redisTemplate).delete(anyString());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown);
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
    }

    @Test
    public void expiredJobsAreDescheduledEvenIfVcsIntegrationFails() {
        Job job = job(JobStatus.pending);
        job.setCreatedDate(DateUtils.addDays(new Date(System.currentTimeMillis()), -1));

        doReturn(null).when(redisTemplate).delete(anyString());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doThrow(new RuntimeException("Boom!")).when(gitLabWebhookService).sendCommitStatus(any(), any());

        Assertions.assertTrue(subject().runExecution(job));

        Assertions.assertEquals(JobStatus.failed, job.getStatus());
    }

    @Test
    public void pendingJobWithPlanChanges() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(null).when(redisTemplate).delete(anyString());
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
        doNothing().when(executorService).execute(any(), any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(executorService, times(1)).execute(any(), any(), any());
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
    }

    @Test
    public void pendingJobNoPlanChanges() {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(false);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        // Called twice :(
        doReturn(null).when(redisTemplate).delete(anyString());
        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(job).when(jobRepository).save(any());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertFalse(subject().runExecution(job));

        verify(jobRepository, times(1)).save(job);
        // TODO Called twice :(
        verify(workspaceRepository, times(2)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(2)).sendCommitStatus(job, JobStatus.completed);
        Assertions.assertEquals(JobStatus.completed, job.getStatus());
        Assertions.assertEquals(JobStatus.notExecuted, job.getStep().get(0).getStatus());
    }

    @Test
    public void pendingJobFailsOnExecutionChanges() throws Exception {
        Job job = job(JobStatus.pending);
        job.setPlanChanges(true);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

        doReturn(null).when(redisTemplate).delete(anyString());
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
        doThrow(new ExecutionException(new Exception("Boom!"))).when(executorService).execute(any(), any(), any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertTrue(subject().runExecution(job));

        verify(jobRepository, times(1)).save(job);
        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.pending, job.getStep().get(0).getStatus());
    }

    @Test
    public void pendingJobWithApprovalFlow() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.approval.name());
        flow.setTeam("ze-team");

        doReturn(null).when(redisTemplate).delete(anyString());
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

        doReturn(null).when(redisTemplate).delete(anyString());
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
        Assertions.assertEquals(JobStatus.pending, job.getStatus());
        Assertions.assertEquals("", job.getApprovalTeam());
    }

    @Test
    public void pendingJobWithDisableWorkspace() throws Exception {
        Job job = job(JobStatus.pending);

        Flow flow = new Flow();
        flow.setType(FlowType.disableWorkspace.name());

        doReturn(null).when(redisTemplate).delete(anyString());
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

        doReturn(null).when(redisTemplate).delete(anyString());
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

        doReturn(null).when(redisTemplate).delete(anyString());
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

        doReturn(null).when(redisTemplate).delete(anyString());
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
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.unknown);
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

        doReturn(null).when(redisTemplate).delete(anyString());
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

        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        // Seems odd that we do not remove the job from the scheduler?
        Assert.assertTrue(subject().runExecution(job));

        verify(jobRepository, times(1)).save(job);
        verify(workspaceRepository, times(2)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.completed);
        Assertions.assertEquals(JobStatus.completed, job.getStatus());
    }

    @Test
    public void approvedJob() throws Exception {
        Job job = job(JobStatus.approved);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

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
        Assertions.assertEquals(JobStatus.approved, job.getStatus());
    }

    @Test
    public void approvedJobFailsOnExecutionError() throws Exception {
        Job job = job(JobStatus.approved);

        Flow flow = new Flow();
        flow.setType(FlowType.terraformPlan.name());

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
        doThrow(new ExecutionException(new Exception("Boom!"))).when(executorService).execute(any(), any(), any());

        // TODO Could be true with no extra scheduling, because we know we are done
        Assert.assertTrue(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        Assertions.assertEquals(JobStatus.failed, job.getStatus());
        Assertions.assertEquals(JobStatus.pending, job.getStep().get(0).getStatus());
    }

     @Test
     public void completedJobWithHistoryGloballyVar() {
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

         doReturn(null).when(redisTemplate).delete(anyString());
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
         doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());
         doNothing().when(jobRepository).delete(any());
          // Passed directly to other mock, so list does not matter
         doReturn(Collections.emptyList()).when(stepRepository).findByJobId(anyInt());
         doNothing().when(stepRepository).deleteAll(anyList());

         Assert.assertTrue(subject().runExecution(job));

         verify(jobRepository, times(1)).delete(any()); // Ensure we do not delete anything else
         verify(jobRepository, times(1)).delete(prev2);
     }

    @Test
    public void completedJobWithHistoryWorkspaceVar() {
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

        doReturn(null).when(redisTemplate).delete(anyString());
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
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());
        doNothing().when(jobRepository).delete(any());
        // Passed directly to other mock, so list does not matter
        doReturn(Collections.emptyList()).when(stepRepository).findByJobId(anyInt());
        doNothing().when(stepRepository).deleteAll(anyList());

        Assert.assertTrue(subject().runExecution(job));

        verify(jobRepository, times(1)).delete(any()); // Ensure we do not delete anything else
        verify(jobRepository, times(1)).delete(prev2);
    }

    @Test
    public void completedJob() {
        Job job = job(JobStatus.completed);

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        // Called twice :(
        doReturn(null).when(redisTemplate).delete(anyString());
        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(workspaceRepository, times(1)).save(job.getWorkspace());
        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.completed);
    }

    @Test
    public void failedJob() {
        Job job = job(JobStatus.failed);

        doReturn(Collections.emptyList()).when(globalVarRepository).findByOrganization(any());
        doReturn(Optional.of(Collections.emptyList())).when(variableRepository).findByWorkspace(any());

        doReturn(Optional.of(Collections.emptyList()))
                .when(jobRepository)
                .findByWorkspaceAndStatusNotInAndIdLessThan(
                        any(Workspace.class),
                        anyList(),
                        anyInt());
        // Called twice :(
        doReturn(null).when(redisTemplate).delete(anyString());
        doReturn(job.getStep()).when(stepRepository).findByJobId(anyInt());
        doReturn(null).when(stepRepository).save(any());
        doReturn(job.getWorkspace()).when(workspaceRepository).save(any());

        doNothing().when(gitLabWebhookService).sendCommitStatus(any(), any());

        Assert.assertTrue(subject().runExecution(job));

        verify(gitLabWebhookService, times(1)).sendCommitStatus(job, JobStatus.failed);
        Assertions.assertEquals(JobStatus.failed, job.getStep().get(0).getStatus());
    }

    @Test
    public void nonActionableStatusJob() {
        Job job = job(JobStatus.queue);

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
}