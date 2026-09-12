package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyDriftDispatchTransactionsTest {

    private JobRepository jobRepository;
    private ScheduleJobService scheduleJobService;
    private PolicyDriftDispatchTransactions transactions;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        scheduleJobService = Mockito.mock(ScheduleJobService.class);
        transactions = new PolicyDriftDispatchTransactions(jobRepository, scheduleJobService);
    }

    @Test
    void testDispatchDriftEvaluationJobFromWorkspace() throws Exception {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("prod-vpc");
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        ws.setOrganization(org);

        Job completedJob = new Job();
        completedJob.setId(77);
        completedJob.setTerraformPlan("http://minio/plan.json");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(ws, List.of(JobStatus.completed)))
                .thenReturn(Optional.of(completedJob));

        Job saved = new Job();
        saved.setId(101);
        when(jobRepository.save(any(Job.class))).thenReturn(saved);

        Job result = transactions.dispatchDriftEvaluationJob(ws);

        assertNotNull(result);
        assertEquals(101, result.getId());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job captured = captor.getValue();
        assertEquals(JobVia.DRIFT.getValue(), captured.getVia());
        assertEquals("serviceAccount", captured.getCreatedBy());
        assertEquals("http://minio/plan.json", captured.getTerraformPlan());
        assertEquals(JobStatus.pending, captured.getStatus());
        assertTrue(captured.isPlanChanges());

        String decodedTcl = new String(Base64.getDecoder().decode(captured.getTcl()), StandardCharsets.UTF_8);
        assertTrue(decodedTcl.contains("type: policyEvaluation"));

        verify(scheduleJobService).createJobContext(saved);
    }

    @Test
    void testDispatchPolicyEvaluationJobWithCustomUserAndVia() throws Exception {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("staging-k8s");

        Job saved = new Job();
        saved.setId(202);
        when(jobRepository.save(any(Job.class))).thenReturn(saved);

        Job result = transactions.dispatchPolicyEvaluationJob(ws, "http://minio/plan2.json", "alice", JobVia.UI.getValue());

        assertNotNull(result);
        assertEquals(202, result.getId());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job captured = captor.getValue();
        assertEquals(JobVia.UI.getValue(), captured.getVia());
        assertEquals("alice", captured.getCreatedBy());
        assertEquals("http://minio/plan2.json", captured.getTerraformPlan());

        verify(scheduleJobService).createJobContext(saved);
    }

    @Test
    void testDispatchPolicyEvaluationJobPropagatesExceptionWhenSchedulerFails() throws Exception {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("test-error");

        Job saved = new Job();
        saved.setId(303);
        when(jobRepository.save(any(Job.class))).thenReturn(saved);
        doThrow(new RuntimeException("Quartz connection lost")).when(scheduleJobService).createJobContext(saved);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                transactions.dispatchPolicyEvaluationJob(ws, "http://minio/plan.json", "alice", JobVia.UI.getValue())
        );

        assertTrue(ex.getMessage().contains("Failed to dispatch policy evaluation job"));
    }
}
