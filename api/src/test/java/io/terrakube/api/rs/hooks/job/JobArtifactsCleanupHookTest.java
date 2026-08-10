package io.terrakube.api.rs.hooks.job;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

class JobArtifactsCleanupHookTest {

    private final StorageTypeService storage = mock(StorageTypeService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobArtifactsCleanupHook hook = new JobArtifactsCleanupHook(storage, jobRepository);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private Job job(JobStatus status, String artifacts) {
        Organization organization = new Organization();
        organization.setId(organizationId);
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Job job = new Job();
        job.setId(42);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(status);
        job.setTerraformPlanArtifacts(artifacts);
        return job;
    }

    private void run(Job job) {
        hook.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, job, null, Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"completed", "failed", "cancelled", "rejected", "noChanges"})
    void aJobThatIsOverHasItsArtifactsDeletedAndTheLocationCleared(JobStatus status) {
        run(job(status, "https://bucket/plan-artifacts/o/w/42/s/plan-artifacts.tar.gz"));

        verify(storage).deleteJobArtifacts(organizationId.toString(), workspaceId.toString(), 42);
        verify(jobRepository).clearPlanArtifactsLocation(42);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"pending", "waitingApproval", "approved", "queue", "running"})
    void aJobStillInProgressKeepsItsArtifactsBecauseTheApplyNeedsThem(JobStatus status) {
        run(job(status, "https://bucket/plan-artifacts/o/w/42/s/plan-artifacts.tar.gz"));

        verify(storage, never()).deleteJobArtifacts(anyString(), anyString(), anyInt());
        verify(jobRepository, never()).clearPlanArtifactsLocation(anyInt());
    }

    @Test
    void aJobWithoutArtifactsCostsNoStorageCall() {
        run(job(JobStatus.completed, null));
        run(job(JobStatus.completed, "  "));

        verify(storage, never()).deleteJobArtifacts(anyString(), anyString(), anyInt());
    }

    @Test
    void aJobWithoutItsOrganizationOrWorkspaceIsSkippedQuietly() {
        Job withoutOrganization = job(JobStatus.completed, "https://bucket/plan-artifacts/o/w/42/s/plan-artifacts.tar.gz");
        withoutOrganization.setOrganization(null);
        Job withoutWorkspace = job(JobStatus.completed, "https://bucket/plan-artifacts/o/w/42/s/plan-artifacts.tar.gz");
        withoutWorkspace.setWorkspace(null);

        run(withoutOrganization);
        run(withoutWorkspace);

        verify(storage, never()).deleteJobArtifacts(anyString(), anyString(), anyInt());
        verify(jobRepository, never()).clearPlanArtifactsLocation(anyInt());
    }

    @Test
    void aStorageProblemNeverFailsTheRequestAndKeepsTheLocationForTheBackstop() {
        doThrow(new RuntimeException("bucket unavailable")).when(storage).deleteJobArtifacts(anyString(), anyString(), anyInt());

        run(job(JobStatus.failed, "https://bucket/plan-artifacts/o/w/42/s/plan-artifacts.tar.gz"));

        verify(jobRepository, never()).clearPlanArtifactsLocation(anyInt());
    }
}
