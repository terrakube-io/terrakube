package io.terrakube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationSweep;
import io.terrakube.api.plugin.scheduler.spool.SpoolRecoveryProperties;
import io.terrakube.api.plugin.scheduler.spool.SpoolRecoveryService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

@Transactional
class StructuredOutputSpoolRecoveryIntegrationTest extends ServerApplicationTests {

    @Autowired
    private StorageTypeService storageTypeService;

    @Autowired
    private SpoolRecoveryService spoolRecoveryService;

    @Autowired
    private SpoolRecoveryProperties spoolRecoveryProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization organization;
    private Workspace workspace;

    @BeforeEach
    void setupTestData() {
        organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        valueOperations = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();
        lenient().doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any(java.time.Duration.class));
        lenient().doReturn(true).when(redisTemplate).delete(anyString());
        Properties redisProps = new Properties();
        redisProps.setProperty("uptime_in_seconds", "3600");
        lenient().doReturn(redisProps).when(redisTemplate).execute(any(RedisCallback.class));
    }

    @AfterEach
    void cleanupSpoolDirectory() {
        try {
            if (organization != null) {
                File orgSpoolDir = new File(FileUtils.getUserDirectoryPath(),
                        "/.terraform-spring-boot/local/context-spool/" + organization.getId());
                if (orgSpoolDir.exists()) {
                    FileUtils.deleteDirectory(orgSpoolDir);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void writeLocalSpooledFile(String orgId, String jobId, String stepId, String phase, Map<String, Object> patch) throws IOException {
        Map<String, Object> envelope = Map.of(
                "organizationId", orgId,
                "jobId", jobId,
                "stepId", stepId,
                "phase", phase,
                "deliveryRevision", 1L,
                "timestamp", System.currentTimeMillis()
        );
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);

        byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(jsonBytes);
        }
        byte[] gzippedBytes = baos.toByteArray();

        String relativePath = String.format("/.terraform-spring-boot/local/context-spool/%s/%s/%s-%s.json.gz", orgId, jobId, stepId, phase);
        File targetFile = new File(FileUtils.getUserDirectoryPath().concat(relativePath));
        targetFile.getParentFile().mkdirs();
        FileUtils.writeByteArrayToFile(targetFile, gzippedBytes);
    }

    @Test
    void testSpoolRecovery_NormalFlow() throws IOException {
        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);
        step = stepRepository.saveAndFlush(step);

        String orgId = organization.getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();

        Map<String, Object> planPatch = Map.of(
                "planStructuredOutput/" + stepId, List.of(Map.of("resource", "aws_s3_bucket.integration_test")),
                "terrakubeUI/" + stepId, "marker",
                "structuredOutputStatus", Map.of("state", "PERSISTED")
        );
        writeLocalSpooledFile(orgId, jobId, stepId, "plan", planPatch);

        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isTrue();

        int recoveredCount = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recoveredCount).isEqualTo(1);

        Step updatedStep = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(updatedStep.getDeliveryState()).isEqualTo(DeliveryState.RECOVERED);

        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isFalse();

        String savedContext = storageTypeService.getContext(job.getId());
        assertThat(savedContext).isNotNull();

        var root = objectMapper.readTree(savedContext);
        // Verify path keys were converted to nested structure, not stored as literal keys with slashes
        assertThat(root.has("planStructuredOutput/" + stepId)).isFalse();
        assertThat(root.path("planStructuredOutput").path(stepId).isArray()).isTrue();
        assertThat(root.path("planStructuredOutput").path(stepId).get(0).path("resource").asText())
                .isEqualTo("aws_s3_bucket.integration_test");
        assertThat(root.path("terrakubeUI").path(stepId).asText()).isEqualTo("marker");
        assertThat(root.path("structuredOutputStatus").path("state").asText()).isEqualTo("PERSISTED");
    }

    @Test
    void testSpoolRecovery_RespectsRevisionsAgainstExistingLiveContext() throws IOException {
        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);
        step = stepRepository.saveAndFlush(step);

        String orgId = organization.getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();
        String otherStepId = UUID.randomUUID().toString();

        // Prepopulate existing context with live entries at revision 50 and 200
        String existingContext = "{\n" +
                "  \"contextRevisions\": {\n" +
                "    \"planStructuredOutput/" + stepId + "\": 50,\n" +
                "    \"planStructuredOutput/" + otherStepId + "\": 200\n" +
                "  },\n" +
                "  \"planStructuredOutput\": {\n" +
                "    \"" + stepId + "\": [{\"resource\": \"aws_s3_bucket.old_live\"}],\n" +
                "    \"" + otherStepId + "\": [{\"resource\": \"aws_s3_bucket.newer_live\"}]\n" +
                "  }\n" +
                "}";
        storageTypeService.saveContext(job.getId(), existingContext);

        // Spool has deliveryRevision 100:
        // - planStructuredOutput/<stepId> (100 > 50 -> should update)
        // - planStructuredOutput/<otherStepId> (100 < 200 -> should skip)
        Map<String, Object> envelope = Map.of(
                "organizationId", orgId,
                "jobId", jobId,
                "stepId", stepId,
                "phase", "plan",
                "deliveryRevision", 100L,
                "timestamp", System.currentTimeMillis()
        );
        Map<String, Object> patch = Map.of(
                "planStructuredOutput/" + stepId, List.of(Map.of("resource", "aws_s3_bucket.spooled_recovered")),
                "planStructuredOutput/" + otherStepId, List.of(Map.of("resource", "aws_s3_bucket.stale_spool"))
        );
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);
        byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(jsonBytes);
        }
        String relativePath = String.format("/.terraform-spring-boot/local/context-spool/%s/%s/%s-plan.json.gz", orgId, jobId, stepId);
        File targetFile = new File(FileUtils.getUserDirectoryPath().concat(relativePath));
        targetFile.getParentFile().mkdirs();
        FileUtils.writeByteArrayToFile(targetFile, baos.toByteArray());

        int recoveredCount = spoolRecoveryService.recoverPendingSpooledSteps();
        assertThat(recoveredCount).isEqualTo(1);

        String savedContext = storageTypeService.getContext(job.getId());
        var root = objectMapper.readTree(savedContext);

        // Newer spool revision applied
        assertThat(root.path("planStructuredOutput").path(stepId).get(0).path("resource").asText())
                .isEqualTo("aws_s3_bucket.spooled_recovered");
        assertThat(root.path("contextRevisions").path("planStructuredOutput/" + stepId).asLong()).isEqualTo(100L);

        // Stale spool revision skipped in favor of existing higher revision
        assertThat(root.path("planStructuredOutput").path(otherStepId).get(0).path("resource").asText())
                .isEqualTo("aws_s3_bucket.newer_live");
        assertThat(root.path("contextRevisions").path("planStructuredOutput/" + otherStepId).asLong()).isEqualTo(200L);
    }

    @Test
    void testSpoolRecovery_FailedJobBypassesConflict() throws IOException {
        Job job = new Job();
        job.setStatus(JobStatus.failed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformApply");
        step.setStatus(JobStatus.failed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 30_000L);
        step = stepRepository.saveAndFlush(step);

        String orgId = organization.getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();

        Map<String, Object> applyPatch = Map.of(
                "jobDiagnostics/" + stepId, List.of(Map.of("summary", "Resource creation timed out")),
                "structuredOutputStatus", Map.of("state", "PERSISTED")
        );
        writeLocalSpooledFile(orgId, jobId, stepId, "apply", applyPatch);

        int recoveredCount = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recoveredCount).isEqualTo(1);

        Step updatedStep = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(updatedStep.getDeliveryState()).isEqualTo(DeliveryState.RECOVERED);

        String savedContext = storageTypeService.getContext(job.getId());
        var root = objectMapper.readTree(savedContext);
        assertThat(root.has("jobDiagnostics/" + stepId)).isFalse();
        assertThat(root.path("jobDiagnostics").path(stepId).get(0).path("summary").asText())
                .isEqualTo("Resource creation timed out");
    }

    @Test
    void testSpoolRecovery_ExpiredSpoolPurged() throws IOException {
        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        // 25 hours ago -> expired
        step.setDeliveryTimestamp(System.currentTimeMillis() - 25 * 3600_000L);
        step = stepRepository.saveAndFlush(step);

        String orgId = organization.getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();

        Map<String, Object> planPatch = Map.of("planStructuredOutput/" + stepId, List.of());
        writeLocalSpooledFile(orgId, jobId, stepId, "plan", planPatch);

        int processed = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(processed).isEqualTo(1);

        Step updatedStep = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(updatedStep.getDeliveryState()).isEqualTo(DeliveryState.EXPIRED);

        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isFalse();
    }

    @Autowired
    private JobReconciliationSweep jobReconciliationSweep;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void testDeadExecutorProbe_RecoversAndFailsJob() throws Exception {
        Job job = new Job();
        job.setStatus(JobStatus.running);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.running);
        step.setDeliveryState(DeliveryState.NOT_DURABLE);
        step = stepRepository.saveAndFlush(step);

        // Update updated_date directly via JDBC to bypass Hibernate @UpdateTimestamp
        jdbcTemplate.update("UPDATE job SET updated_date = ? WHERE id = ?",
                new Date(System.currentTimeMillis() - 600_000L), job.getId());
        entityManager.clear();

        String orgId = organization.getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();

        Map<String, Object> planPatch = Map.of(
                "planStructuredOutput/" + stepId, List.of(Map.of("resource", "aws_iam_role.dead_executor_recovered")),
                "structuredOutputStatus", Map.of("state", "PERSISTED")
        );
        writeLocalSpooledFile(orgId, jobId, stepId, "plan", planPatch);
        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isTrue();

        // Executor heartbeat is missing
        lenient().doReturn(false).when(redisTemplate).hasKey("executor-job-heartbeat:" + job.getId());

        jobReconciliationSweep.execute(null);

        // Verify dead executor probe recovered the step output
        Step recoveredStep = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(recoveredStep.getDeliveryState()).isEqualTo(DeliveryState.RECOVERED);
        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isFalse();

        // Verify context has the recovered output nested correctly
        String context = storageTypeService.getContext(job.getId());
        var root = objectMapper.readTree(context);
        assertThat(root.has("planStructuredOutput/" + stepId)).isFalse();
        assertThat(root.path("planStructuredOutput").path(stepId).get(0).path("resource").asText())
                .isEqualTo("aws_iam_role.dead_executor_recovered");

        // Verify job was failed due to heartbeat expiry
        Job failedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(JobStatus.failed);
    }
}
