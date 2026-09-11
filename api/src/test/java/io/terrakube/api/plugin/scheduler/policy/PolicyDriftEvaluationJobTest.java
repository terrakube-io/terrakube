package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.policy.PolicyResolutionService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyDriftEvaluationJobTest {

    private WorkspaceRepository workspaceRepository;
    private JobRepository jobRepository;
    private ScheduleJobService scheduleJobService;
    private PolicyResolutionService policyResolutionService;
    private PolicyDriftEvaluationJob driftJob;

    @BeforeEach
    void setUp() {
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        scheduleJobService = Mockito.mock(ScheduleJobService.class);
        policyResolutionService = Mockito.mock(PolicyResolutionService.class);

        driftJob = new PolicyDriftEvaluationJob(
                workspaceRepository,
                jobRepository,
                scheduleJobService,
                policyResolutionService,
                50,
                100,
                5,
                2,
                12
        );
    }

    @Test
    void testExecuteDispatchesDriftJobForCompliantWorkspaces() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("AcmeCorp");

        Workspace wsWithPolicies = new Workspace();
        wsWithPolicies.setId(UUID.randomUUID());
        wsWithPolicies.setName("ws-compliant");
        wsWithPolicies.setOrganization(org);
        wsWithPolicies.setLastJobStatus(JobStatus.completed);
        wsWithPolicies.setLocked(false);
        wsWithPolicies.setDeleted(false);

        Workspace wsNeverExecuted = new Workspace();
        wsNeverExecuted.setId(UUID.randomUUID());
        wsNeverExecuted.setName("ws-fresh");
        wsNeverExecuted.setOrganization(org);
        wsNeverExecuted.setLastJobStatus(JobStatus.NeverExecuted); // Skip

        Job completedJob = new Job();
        completedJob.setId(500);
        completedJob.setTerraformPlan("http://storage/tfstate/sample/plan");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(wsWithPolicies, List.of(JobStatus.completed)))
                .thenReturn(Optional.of(completedJob));

        when(workspaceRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wsWithPolicies, wsNeverExecuted)));

        PolicyContext pc = PolicyContext.builder().policyId(UUID.randomUUID().toString()).build();
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of(pc));

        Job savedJob = new Job();
        savedJob.setId(999);
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        driftJob.execute(context);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(jobCaptor.capture());

        Job dispatched = jobCaptor.getValue();
        assertNotNull(dispatched);
        assertNotNull(dispatched.getTcl());
        assertTrue(dispatched.isPlanChanges());
        assertEquals(JobVia.DRIFT.getValue(), dispatched.getVia());
        assertEquals("http://storage/tfstate/sample/plan", dispatched.getTerraformPlan());

        String decodedTcl = new String(Base64.getDecoder().decode(dispatched.getTcl()), StandardCharsets.UTF_8);
        assertTrue(decodedTcl.contains("type: policyEvaluation"));
        verify(scheduleJobService).createJobContext(savedJob);
    }

    @Test
    void testExecuteSkipsWorkspacesWithoutCompletedPlan() throws JobExecutionException {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace wsNoPlan = new Workspace();
        wsNoPlan.setId(UUID.randomUUID());
        wsNoPlan.setName("ws-no-plan");
        wsNoPlan.setOrganization(org);
        wsNoPlan.setLastJobStatus(JobStatus.completed);
        wsNoPlan.setLocked(false);
        wsNoPlan.setDeleted(false);

        when(workspaceRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wsNoPlan)));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(wsNoPlan, List.of(JobStatus.completed)))
                .thenReturn(Optional.empty());

        driftJob.execute(context);

        verify(policyResolutionService, never()).resolvePoliciesForJob(any(Job.class));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void testExecuteSkipsWorkspacesWithoutPolicies() throws JobExecutionException {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace wsNoPolicies = new Workspace();
        wsNoPolicies.setId(UUID.randomUUID());
        wsNoPolicies.setOrganization(org);
        wsNoPolicies.setLastJobStatus(JobStatus.completed);
        wsNoPolicies.setLocked(false);
        wsNoPolicies.setDeleted(false);

        Job completedJob = new Job();
        completedJob.setTerraformPlan("http://storage/plan");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(wsNoPolicies, List.of(JobStatus.completed)))
                .thenReturn(Optional.of(completedJob));

        when(workspaceRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wsNoPolicies)));
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of()); // No policies

        driftJob.execute(context);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void testExecuteWithMultiplePages() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("AcmeCorp");

        Workspace ws1 = new Workspace();
        ws1.setId(UUID.randomUUID());
        ws1.setName("ws-page-1");
        ws1.setOrganization(org);
        ws1.setLastJobStatus(JobStatus.completed);

        Workspace ws2 = new Workspace();
        ws2.setId(UUID.randomUUID());
        ws2.setName("ws-page-2");
        ws2.setOrganization(org);
        ws2.setLastJobStatus(JobStatus.completed);

        Job completedJob = new Job();
        completedJob.setId(101);
        completedJob.setTerraformPlan("http://storage/plan");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(any(Workspace.class), any()))
                .thenReturn(Optional.of(completedJob));

        PolicyContext pc = PolicyContext.builder().policyId(UUID.randomUUID().toString()).build();
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of(pc));

        Job savedJob = new Job();
        savedJob.setId(102);
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        // Page 0 has content and next page (total 2 elements, pageSize 1)
        Page<Workspace> page0 = new PageImpl<>(List.of(ws1), PageRequest.of(0, 1), 2);
        Page<Workspace> page1 = new PageImpl<>(List.of(ws2), PageRequest.of(1, 1), 2);

        when(workspaceRepository.findAll(PageRequest.of(0, 50))).thenReturn(page0);
        when(workspaceRepository.findAll(PageRequest.of(1, 50))).thenReturn(page1);

        driftJob.execute(context);

        // Both workspaces across both pages should be dispatched
        verify(jobRepository, times(2)).save(any(Job.class));
    }

    @Test
    void testExecuteThrottlesAtMaxJobsPerRun() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        // Create a driftJob with maxJobsPerRun = 2
        PolicyDriftEvaluationJob throttledDriftJob = new PolicyDriftEvaluationJob(
                workspaceRepository,
                jobRepository,
                scheduleJobService,
                policyResolutionService,
                10,
                2,
                10,
                2,
                12
        );

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace ws1 = new Workspace();
        ws1.setId(UUID.randomUUID());
        ws1.setName("ws-1");
        ws1.setOrganization(org);
        ws1.setLastJobStatus(JobStatus.completed);

        Workspace ws2 = new Workspace();
        ws2.setId(UUID.randomUUID());
        ws2.setName("ws-2");
        ws2.setOrganization(org);
        ws2.setLastJobStatus(JobStatus.completed);

        Workspace ws3 = new Workspace();
        ws3.setId(UUID.randomUUID());
        ws3.setName("ws-3");
        ws3.setOrganization(org);
        ws3.setLastJobStatus(JobStatus.completed);

        Job completedJob = new Job();
        completedJob.setId(201);
        completedJob.setTerraformPlan("http://storage/plan");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(any(Workspace.class), any()))
                .thenReturn(Optional.of(completedJob));

        PolicyContext pc = PolicyContext.builder().policyId(UUID.randomUUID().toString()).build();
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of(pc));

        Job savedJob = new Job();
        savedJob.setId(202);
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        Page<Workspace> page = new PageImpl<>(List.of(ws1, ws2, ws3), PageRequest.of(0, 10), 3);
        when(workspaceRepository.findAll(PageRequest.of(0, 10))).thenReturn(page);

        throttledDriftJob.execute(context);

        // Even though 3 workspaces were eligible, only 2 should be dispatched due to maxJobsPerRun = 2
        verify(jobRepository, times(2)).save(any(Job.class));
    }

    @Test
    void testExecuteSkipsWhenActiveDriftJobsExceedCeiling() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);
        Scheduler scheduler = Mockito.mock(Scheduler.class);
        JobDetail jobDetail = Mockito.mock(JobDetail.class);
        when(context.getScheduler()).thenReturn(scheduler);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getKey()).thenReturn(new JobKey("driftJobKey"));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        // Active drift jobs already equals ceiling (5)
        when(jobRepository.countByViaAndStatusIn(eq(JobVia.DRIFT.getValue()), any())).thenReturn(5L);

        driftJob.execute(context);

        // Should not query workspace repository or save any jobs
        verify(workspaceRepository, never()).findAll(any(Pageable.class));
        verify(jobRepository, never()).save(any(Job.class));

        // Should schedule follow-up trigger
        verify(scheduler).scheduleJob(any(Trigger.class));
    }

    @Test
    void testExecuteHaltsMidScanWhenActiveDriftJobsHitCeiling() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);
        Scheduler scheduler = Mockito.mock(Scheduler.class);
        JobDetail jobDetail = Mockito.mock(JobDetail.class);
        when(context.getScheduler()).thenReturn(scheduler);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getKey()).thenReturn(new JobKey("driftJobKey"));
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        // Active drift jobs starts at 4, ceiling is 5
        when(jobRepository.countByViaAndStatusIn(eq(JobVia.DRIFT.getValue()), any())).thenReturn(4L);

        Workspace ws1 = new Workspace();
        ws1.setId(UUID.randomUUID());
        ws1.setName("ws-1");
        ws1.setLastJobStatus(JobStatus.completed);

        Workspace ws2 = new Workspace();
        ws2.setId(UUID.randomUUID());
        ws2.setName("ws-2");
        ws2.setLastJobStatus(JobStatus.completed);

        Job completedJob = new Job();
        completedJob.setId(301);
        completedJob.setTerraformPlan("http://storage/plan");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(any(Workspace.class), any()))
                .thenReturn(Optional.of(completedJob));

        PolicyContext pc = PolicyContext.builder().policyId(UUID.randomUUID().toString()).build();
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of(pc));

        Job savedJob = new Job();
        savedJob.setId(302);
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        Page<Workspace> page = new PageImpl<>(List.of(ws1, ws2), PageRequest.of(0, 50), 2);
        when(workspaceRepository.findAll(any(Pageable.class))).thenReturn(page);

        driftJob.execute(context);

        // Only ws1 is dispatched; ws2 is halted because activeDriftJobs reached 5
        verify(jobRepository, times(1)).save(any(Job.class));
        verify(scheduler).scheduleJob(any(Trigger.class));
    }

    @Test
    void testExecuteSkipsWorkspaceWithActiveJobs() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Workspace wsWithActiveJob = new Workspace();
        wsWithActiveJob.setId(UUID.randomUUID());
        wsWithActiveJob.setName("ws-busy");
        wsWithActiveJob.setLastJobStatus(JobStatus.running);

        when(workspaceRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wsWithActiveJob)));

        // Workspace has active job ID 42
        when(jobRepository.findActiveJobIdsByWorkspace(wsWithActiveJob.getId().toString()))
                .thenReturn(List.of(42));

        driftJob.execute(context);

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void testExecuteSkipsWorkspaceWithRecentDriftEvaluation() throws Exception {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        Workspace wsRecentDrift = new Workspace();
        wsRecentDrift.setId(UUID.randomUUID());
        wsRecentDrift.setName("ws-recent");
        wsRecentDrift.setLastJobStatus(JobStatus.completed);

        when(workspaceRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wsRecentDrift)));

        // Latest job on workspace was via "Drift" created 1 hour ago (within 12h cooldown)
        Job recentDriftJob = new Job();
        recentDriftJob.setId(401);
        recentDriftJob.setVia(JobVia.DRIFT.getValue());
        recentDriftJob.setCreatedDate(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(jobRepository.findFirstByWorkspaceOrderByIdDesc(wsRecentDrift))
                .thenReturn(Optional.of(recentDriftJob));

        driftJob.execute(context);

        verify(jobRepository, never()).save(any(Job.class));
    }
}
