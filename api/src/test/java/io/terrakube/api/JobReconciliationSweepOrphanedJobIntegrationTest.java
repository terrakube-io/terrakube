package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationSweep;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@Transactional
class JobReconciliationSweepOrphanedJobIntegrationTest extends ServerApplicationTests {

    @Autowired
    private JobReconciliationSweep jobReconciliationSweep;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void cancelsARunningJobWhoseWorkspaceWasSoftDeleted() throws Exception {
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any(java.time.Duration.class));
        lenient().doReturn(true).when(redisTemplate).delete(anyString());
        Properties redisProps = new Properties();
        redisProps.setProperty("uptime_in_seconds", "3600");
        lenient().doReturn(redisProps).when(redisTemplate).execute(any(RedisCallback.class));

        Job job = new Job();
        job.setStatus(JobStatus.running);
        job.setOrganization(organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow());
        job.setWorkspace(workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow());
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.running);
        step = stepRepository.saveAndFlush(step);

        // The job now loads without its workspace, as the @SQLRestriction on Workspace hides it.
        jdbcTemplate.update("UPDATE workspace SET deleted = true WHERE id = ?", job.getWorkspace().getId());
        entityManager.clear();

        jobReconciliationSweep.execute(null);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM job WHERE id = ?", String.class, job.getId()))
                .isEqualTo(JobStatus.cancelled.name());
        assertThat(jdbcTemplate.queryForObject("SELECT workspace_id FROM job WHERE id = ?", String.class, job.getId()))
                .isNotNull();
        assertThat(stepRepository.findById(step.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.cancelled);
    }
}
