package io.terrakube.api.plugin.scheduler.spool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.context.ContextReadService;
import io.terrakube.api.plugin.policy.PolicyEvaluationService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpoolRecoveryServiceTest {

    private StepRepository stepRepository;
    private JobRepository jobRepository;
    private StorageTypeService storageTypeService;
    private ContextReadService contextReadService;
    private PolicyEvaluationService policyEvaluationService;
    private ObjectMapper objectMapper;
    private SpoolRecoveryProperties properties;
    private SpoolRecoveryService spoolRecoveryService;

    @BeforeEach
    void setup() {
        stepRepository = mock(StepRepository.class);
        jobRepository = mock(JobRepository.class);
        storageTypeService = mock(StorageTypeService.class);
        contextReadService = mock(ContextReadService.class);
        policyEvaluationService = mock(PolicyEvaluationService.class);
        objectMapper = new ObjectMapper();
        properties = new SpoolRecoveryProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setRetentionHours(24);
        properties.setLeaseDurationSeconds(60);

        spoolRecoveryService = new SpoolRecoveryService(
                stepRepository,
                jobRepository,
                storageTypeService,
                objectMapper,
                properties,
                contextReadService,
                policyEvaluationService);
    }

    private byte[] createGzippedPayload(Map<String, Object> payload) throws IOException {
        byte[] json = objectMapper.writeValueAsBytes(payload);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(json);
        }
        return baos.toByteArray();
    }

    @Test
    void recoverPendingSpooledSteps_Success() throws IOException {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L); // 1 minute ago

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        Map<String, Object> envelope = Map.of(
                "organizationId", orgId.toString(),
                "jobId", String.valueOf(jobId),
                "stepId", stepId.toString(),
                "phase", "plan"
        );
        Map<String, Object> patch = Map.of(
                "planStructuredOutput", Map.of(stepId.toString(), List.of(Map.of("resource", "aws_s3_bucket.b")))
        );
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);
        byte[] gzippedBytes = createGzippedPayload(payload);

        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan"))
                .thenReturn(gzippedBytes);
        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply"))
                .thenReturn(null);
        when(storageTypeService.getContext(jobId)).thenReturn("{\"existing\":\"data\"}");

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(1);
        ArgumentCaptor<String> savedContextCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageTypeService).saveContext(eq(jobId), savedContextCaptor.capture());

        String savedJson = savedContextCaptor.getValue();
        assertThat(savedJson).contains("existing").contains("aws_s3_bucket.b");

        verify(contextReadService).invalidate(eq(jobId), anyString());
        verify(policyEvaluationService).processPolicyEvaluationContext(eq(jobId), anyString());
        verify(stepRepository).updateDeliveryState(stepId, DeliveryState.RECOVERED);
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan");
    }

    @Test
    void recoverPendingSpooledSteps_Expired() {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        // 25 hours ago -> expired
        step.setDeliveryTimestamp(System.currentTimeMillis() - 25 * 3600_000L);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(1);
        verify(stepRepository).updateDeliveryState(stepId, DeliveryState.EXPIRED);
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan");
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply");
        verify(storageTypeService, never()).saveContext(anyInt(), anyString());
    }

    @Test
    void recoverPendingSpooledSteps_LeaseCollision() {
        UUID stepId = UUID.randomUUID();
        Step step = new Step();
        step.setId(stepId);
        step.setDeliveryState(DeliveryState.SPOOLED);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        // Another worker won the lease
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(0);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(0);
        verify(storageTypeService, never()).getSpooledContextPatch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recoverPendingSpooledSteps_Disabled() {
        properties.setEnabled(false);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(0);
        verify(stepRepository, never()).findByDeliveryStateAndLeaseUntilBefore(any(), any(), any());
    }

    @Test
    void decompressionBomb_ThrowsExceptionAndRejects() throws IOException {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        // Set uncompressed limit to 50 bytes
        properties.setMaxUncompressedBytes(50);

        Map<String, Object> envelope = Map.of(
                "organizationId", orgId.toString(),
                "jobId", String.valueOf(jobId),
                "stepId", stepId.toString(),
                "phase", "plan"
        );
        Map<String, Object> patch = Map.of("largeField", "A".repeat(500));
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);
        byte[] gzippedBytes = createGzippedPayload(payload);

        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan"))
                .thenReturn(gzippedBytes);
        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply"))
                .thenReturn(null);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(0);
        // Should delete corrupted spool
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan");
        verify(storageTypeService, never()).saveContext(anyInt(), anyString());
    }

    @Test
    void compressedPayloadExceedsLimit_RejectsAndPurges() {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        // Set compressed limit very low
        properties.setMaxCompressedBytes(10);
        byte[] oversizedCompressed = new byte[50];

        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan"))
                .thenReturn(oversizedCompressed);
        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply"))
                .thenReturn(null);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(0);
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan");
        verify(storageTypeService, never()).saveContext(anyInt(), anyString());
    }

    @Test
    void envelopeMismatch_RejectsAndPurges() throws IOException {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        // Envelope with mismatched organization ID
        Map<String, Object> envelope = Map.of(
                "organizationId", UUID.randomUUID().toString(),
                "jobId", String.valueOf(jobId),
                "stepId", stepId.toString(),
                "phase", "plan"
        );
        Map<String, Object> patch = Map.of("key", "val");
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);
        byte[] gzippedBytes = createGzippedPayload(payload);

        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan"))
                .thenReturn(gzippedBytes);
        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply"))
                .thenReturn(null);

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(0);
        verify(storageTypeService).deleteSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan");
        verify(storageTypeService, never()).saveContext(anyInt(), anyString());
    }

    @Test
    void sensitiveDataRedactedByContextSanitizer() throws IOException {
        UUID orgId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        int jobId = 100;

        Organization org = new Organization();
        org.setId(orgId);

        Job job = new Job();
        job.setId(jobId);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(stepId);
        step.setJob(job);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setDeliveryTimestamp(System.currentTimeMillis() - 60_000L);

        io.terrakube.api.plugin.context.ContextSanitizer contextSanitizer = new io.terrakube.api.plugin.context.ContextSanitizer(objectMapper);
        spoolRecoveryService = new SpoolRecoveryService(
                stepRepository,
                jobRepository,
                storageTypeService,
                objectMapper,
                properties,
                contextReadService,
                policyEvaluationService,
                contextSanitizer);

        when(stepRepository.findByDeliveryStateAndLeaseUntilBefore(eq(DeliveryState.SPOOLED), any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(step));
        when(stepRepository.acquireLease(eq(stepId), anyString(), any(Date.class), any(Date.class)))
                .thenReturn(1);

        Map<String, Object> envelope = Map.of(
                "organizationId", orgId.toString(),
                "jobId", String.valueOf(jobId),
                "stepId", stepId.toString(),
                "phase", "plan"
        );
        Map<String, Object> patch = Map.of(
                "planStructuredOutput", Map.of(
                        stepId.toString(), List.of(
                                Map.of(
                                        "resource", "vault_secret.s",
                                        "after", "secret-password",
                                        "afterSensitive", true
                                )
                        )
                )
        );
        Map<String, Object> payload = Map.of("envelope", envelope, "patch", patch);
        byte[] gzippedBytes = createGzippedPayload(payload);

        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "plan"))
                .thenReturn(gzippedBytes);
        when(storageTypeService.getSpooledContextPatch(orgId.toString(), String.valueOf(jobId), stepId.toString(), "apply"))
                .thenReturn(null);
        when(storageTypeService.getContext(jobId)).thenReturn("{}");

        int recovered = spoolRecoveryService.recoverPendingSpooledSteps();

        assertThat(recovered).isEqualTo(1);
        ArgumentCaptor<String> savedContextCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageTypeService).saveContext(eq(jobId), savedContextCaptor.capture());

        String savedJson = savedContextCaptor.getValue();
        // The sensitive value must be redacted (null)
        assertThat(savedJson).doesNotContain("secret-password");
        assertThat(savedJson).contains("\"after\":null");
    }
}
