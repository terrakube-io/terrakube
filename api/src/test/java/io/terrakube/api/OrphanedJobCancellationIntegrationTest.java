package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJob;
import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationSweep;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class OrphanedJobCancellationIntegrationTest extends ServerApplicationTests {

    @Autowired
    private JobReconciliationSweep jobReconciliationSweep;

    @Autowired
    private ScheduleJob scheduleJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Organization organization;
    private Workspace workspace;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);

        workspace = new Workspace();
        workspace.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.6.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);
    }

    @AfterEach
    void cleanup() {
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM step WHERE job_id IN (SELECT id FROM job WHERE workspace_id = ?)", workspace.getId().toString());
            jdbcTemplate.update("DELETE FROM job WHERE workspace_id = ?", workspace.getId().toString());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId().toString());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId().toString());
        }
    }

    @Test
    void scheduleJobCancelsOrphanedJobOnSoftDeletedWorkspaceWithoutNullWorkspaceIdViolation() {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.pending);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(100);
        step.setStatus(JobStatus.pending);
        stepRepository.save(step);

        UUID originalWorkspaceId = workspace.getId();

        // Soft-delete the workspace so @SQLRestriction makes it invisible to JPA
        workspace.setDeleted(true);
        workspaceRepository.save(workspace);

        // Load the job through JPA: job.getWorkspace() must be null due to @SQLRestriction("deleted = false")
        Job loadedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(loadedJob.getWorkspace()).isNull();

        // Executing ScheduleJob on the orphaned job must not throw ConstraintViolationException
        Job finalJob = loadedJob;
        assertThatCode(() -> {
            boolean deschedule = scheduleJob.runExecution(finalJob);
            assertThat(deschedule).isTrue();
        }).doesNotThrowAnyException();

        // In the database, status should be updated to cancelled
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM job WHERE id = ?", String.class, job.getId());
        assertThat(status).isEqualTo(JobStatus.cancelled.name());

        // And workspace_id column must still hold the original workspace ID
        String persistedWorkspaceId = jdbcTemplate.queryForObject(
                "SELECT workspace_id FROM job WHERE id = ?", String.class, job.getId());
        assertThat(persistedWorkspaceId).isEqualTo(originalWorkspaceId.toString());
    }

    @Test
    void reconciliationSweepCancelsOrphanedJobOnSoftDeletedWorkspaceWithoutConstraintViolation() {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.pending);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(100);
        step.setStatus(JobStatus.pending);
        stepRepository.save(step);

        UUID originalWorkspaceId = workspace.getId();

        // Soft-delete the workspace
        workspace.setDeleted(true);
        workspaceRepository.save(workspace);

        // Execute JobReconciliationSweep: must process orphaned job cleanly without throwing ConstraintViolationException
        assertThatCode(() -> jobReconciliationSweep.execute(null)).doesNotThrowAnyException();

        // Status of job and step should both be cancelled
        String jobStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM job WHERE id = ?", String.class, job.getId());
        assertThat(jobStatus).isEqualTo(JobStatus.cancelled.name());

        String stepStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM step WHERE id = ?", String.class, step.getId());
        assertThat(stepStatus).isEqualTo(JobStatus.cancelled.name());

        // workspace_id in the database must not be null
        String persistedWorkspaceId = jdbcTemplate.queryForObject(
                "SELECT workspace_id FROM job WHERE id = ?", String.class, job.getId());
        assertThat(persistedWorkspaceId).isEqualTo(originalWorkspaceId.toString());
    }
}
