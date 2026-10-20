package io.terrakube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Non-transactional integration test verifying the recovery sweeper path.
 * Runs without class-level @Transactional to ensure repository modifying queries
 * (acquireLease, updateDeliveryState, releaseLease) execute with proper transaction boundaries.
 */
class StructuredOutputSpoolRecoveryNonTransactionalIntegrationTest extends ServerApplicationTests {

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
    private Job job;
    private Step step;

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
    void cleanup() {
        try {
            if (step != null && step.getId() != null) {
                stepRepository.deleteById(step.getId());
            }
            if (job != null) {
                jobRepository.deleteById(job.getId());
            }
        } catch (Exception ignored) {
        }

        try {
            File spoolDir = new File(FileUtils.getUserDirectoryPath(), "/.terraform-spring-boot/local/context-spool");
            if (spoolDir.exists()) {
                FileUtils.deleteDirectory(spoolDir);
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
    void recoverPendingSpooledSteps_WithoutAmbientTransaction_SuccessfullyRecoversStep() throws IOException {
        job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        step = new Step();
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
                "planStructuredOutput/" + stepId, List.of(Map.of("resource", "aws_s3_bucket.nontransactional_test")),
                "terrakubeUI/" + stepId, "marker",
                "structuredOutputStatus", Map.of("state", "PERSISTED")
        );
        writeLocalSpooledFile(orgId, jobId, stepId, "plan", planPatch);

        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isTrue();

        // Run recovery without an ambient Spring transaction
        int recoveredCount = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recoveredCount).isEqualTo(1);

        Step updatedStep = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(updatedStep.getDeliveryState()).isEqualTo(DeliveryState.RECOVERED);
        assertThat(storageTypeService.hasSpooledContextPatch(orgId, jobId, stepId, "plan")).isFalse();

        String savedContext = storageTypeService.getContext(job.getId());
        var root = objectMapper.readTree(savedContext);
        assertThat(root.has("planStructuredOutput/" + stepId)).isFalse();
        assertThat(root.path("planStructuredOutput").path(stepId).get(0).path("resource").asText())
                .isEqualTo("aws_s3_bucket.nontransactional_test");
    }
}
