package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.policy.PolicyResolutionService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
                policyResolutionService
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

        when(workspaceRepository.findAll()).thenReturn(List.of(wsWithPolicies, wsNeverExecuted));

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

        String decodedTcl = new String(Base64.getDecoder().decode(dispatched.getTcl()), StandardCharsets.UTF_8);
        assertTrue(decodedTcl.contains("type: policyEvaluation"));
        verify(scheduleJobService).createJobContextNow(savedJob);
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

        when(workspaceRepository.findAll()).thenReturn(List.of(wsNoPolicies));
        when(policyResolutionService.resolvePoliciesForJob(any(Job.class))).thenReturn(List.of()); // No policies

        driftJob.execute(context);

        verify(jobRepository, never()).save(any(Job.class));
    }
}
