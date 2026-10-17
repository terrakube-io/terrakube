package io.terrakube.api.plugin.scheduler.spool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.context.ContextReadService;
import io.terrakube.api.plugin.policy.PolicyEvaluationService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import io.terrakube.api.plugin.context.ContextSanitizer;

@Slf4j
@Service
public class SpoolRecoveryService {

    private final StepRepository stepRepository;
    private final JobRepository jobRepository;
    private final StorageTypeService storageTypeService;
    private final ObjectMapper objectMapper;
    private final SpoolRecoveryProperties properties;
    private final ContextReadService contextReadService;
    private final PolicyEvaluationService policyEvaluationService;
    private final ContextSanitizer contextSanitizer;

    @Autowired
    public SpoolRecoveryService(
            StepRepository stepRepository,
            JobRepository jobRepository,
            StorageTypeService storageTypeService,
            ObjectMapper objectMapper,
            SpoolRecoveryProperties properties,
            @Autowired(required = false) ContextReadService contextReadService,
            @Autowired(required = false) PolicyEvaluationService policyEvaluationService,
            @Autowired(required = false) ContextSanitizer contextSanitizer) {
        this.stepRepository = stepRepository;
        this.jobRepository = jobRepository;
        this.storageTypeService = storageTypeService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.contextReadService = contextReadService;
        this.policyEvaluationService = policyEvaluationService;
        this.contextSanitizer = contextSanitizer;
    }

    public SpoolRecoveryService(
            StepRepository stepRepository,
            JobRepository jobRepository,
            StorageTypeService storageTypeService,
            ObjectMapper objectMapper,
            SpoolRecoveryProperties properties,
            ContextReadService contextReadService,
            PolicyEvaluationService policyEvaluationService) {
        this(stepRepository, jobRepository, storageTypeService, objectMapper, properties, contextReadService, policyEvaluationService, null);
    }

    public int recoverPendingSpooledSteps() {
        if (!properties.isEnabled()) {
            return 0;
        }

        Date now = new Date();
        List<Step> spooledSteps = stepRepository.findByDeliveryStateAndLeaseUntilBefore(
                DeliveryState.SPOOLED, now, PageRequest.of(0, properties.getBatchSize()));

        if (spooledSteps == null || spooledSteps.isEmpty()) {
            return 0;
        }

        int recoveredCount = 0;
        String workerId = "spool-worker-" + UUID.randomUUID();
        Date leaseUntil = new Date(System.currentTimeMillis() + properties.getLeaseDurationSeconds() * 1000L);

        for (Step step : spooledSteps) {
            int leased = stepRepository.acquireLease(step.getId(), workerId, leaseUntil, now);
            if (leased == 0) {
                log.debug("Step {} already leased by another worker", step.getId());
                continue;
            }
            try {
                if (recoverStep(step)) {
                    recoveredCount++;
                }
            } catch (Exception e) {
                log.error("Failed to recover step {}: {}", step.getId(), e.getMessage(), e);
            }
        }
        return recoveredCount;
    }

    public boolean recoverStep(Step step) {
        Job job = step.getJob();
        if (job == null && step.getId() != null) {
            Optional<Step> freshStep = stepRepository.findById(step.getId());
            if (freshStep.isPresent()) {
                step = freshStep.get();
                job = step.getJob();
            }
        }

        if (job == null || job.getOrganization() == null) {
            log.warn("Cannot recover step {}: job or organization is null", step.getId());
            clearLease(step);
            return false;
        }

        String orgId = job.getOrganization().getId().toString();
        String jobId = String.valueOf(job.getId());
        String stepId = step.getId().toString();

        long timestamp = step.getDeliveryTimestamp() != null ? step.getDeliveryTimestamp() :
                (job.getCreatedDate() != null ? job.getCreatedDate().getTime() : 0L);

        if (timestamp > 0 && (System.currentTimeMillis() - timestamp > properties.getRetentionHours() * 3600_000L)) {
            log.warn("Spooled output for step {} job {} expired (> {} hours), marking EXPIRED and cleaning up",
                    stepId, jobId, properties.getRetentionHours());
            stepRepository.updateDeliveryState(step.getId(), DeliveryState.EXPIRED);
            for (String phase : List.of("plan", "apply")) {
                storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
            }
            return true;
        }

        boolean anyRecovered = false;
        for (String phase : List.of("plan", "apply")) {
            byte[] spooledBytes = storageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
            if (spooledBytes != null && spooledBytes.length > 0) {
                if (spooledBytes.length > properties.getMaxCompressedBytes()) {
                    log.error("Spooled payload for step {} job {} phase {} exceeds max compressed bytes ({} > {}). Deleting corrupted spool.",
                            stepId, jobId, phase, spooledBytes.length, properties.getMaxCompressedBytes());
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                    continue;
                }

                try {
                    byte[] uncompressed = gunzip(spooledBytes, properties.getMaxUncompressedBytes());
                    Map<String, Object> payload = objectMapper.readValue(uncompressed, new TypeReference<Map<String, Object>>() {});

                    @SuppressWarnings("unchecked")
                    Map<String, Object> envelope = (Map<String, Object>) payload.get("envelope");
                    if (!validateEnvelope(envelope, orgId, jobId, stepId, phase)) {
                        log.error("Security envelope mismatch for step {} job {} phase {}. Expected org={}, job={}, step={}, phase={}, but got {}. Deleting invalid spool.",
                                stepId, jobId, phase, orgId, jobId, stepId, phase, envelope);
                        storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = (Map<String, Object>) payload.get("patch");
                    if (patch != null && !patch.isEmpty()) {
                        mergeAndSaveContext(job.getId(), patch);
                        anyRecovered = true;
                    }
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                } catch (Exception e) {
                    log.error("Failed to decompress, parse or apply spooled patch for step {} job {} phase {}: {}. Deleting corrupted spool.",
                            stepId, jobId, phase, e.getMessage(), e);
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                }
            }
        }

        if (anyRecovered) {
            stepRepository.updateDeliveryState(step.getId(), DeliveryState.RECOVERED);
            log.info("Successfully recovered spooled structured output for step {} job {}", stepId, jobId);
            return true;
        } else {
            log.debug("No spooled context patch found for step {} job {}", stepId, jobId);
            clearLease(step);
            return false;
        }
    }

    private boolean validateEnvelope(Map<String, Object> envelope, String expectedOrgId, String expectedJobId, String expectedStepId, String expectedPhase) {
        if (envelope == null) {
            return false;
        }
        Object envOrg = envelope.get("organizationId");
        Object envJob = envelope.get("jobId");
        Object envStep = envelope.get("stepId");
        Object envPhase = envelope.get("phase");

        return expectedOrgId.equals(envOrg != null ? String.valueOf(envOrg) : null)
                && expectedJobId.equals(envJob != null ? String.valueOf(envJob) : null)
                && expectedStepId.equals(envStep != null ? String.valueOf(envStep) : null)
                && expectedPhase.equalsIgnoreCase(envPhase != null ? String.valueOf(envPhase) : null);
    }

    public void mergeAndSaveContext(int jobId, Map<String, Object> patch) throws IOException {
        String currentContextJson = storageTypeService.getContext(jobId);
        Map<String, Object> currentContext = new LinkedHashMap<>();
        if (currentContextJson != null && !currentContextJson.isBlank()) {
            try {
                currentContext = objectMapper.readValue(currentContextJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Unable to parse current context JSON for job {}, starting with fresh context", jobId, e);
                currentContext = new LinkedHashMap<>();
            }
        }
        mergeContextMaps(currentContext, patch);
        String updatedContextJson = objectMapper.writeValueAsString(currentContext);
        if (contextSanitizer != null) {
            updatedContextJson = contextSanitizer.sanitize(updatedContextJson);
        }
        storageTypeService.saveContext(jobId, updatedContextJson);
        if (contextReadService != null) {
            contextReadService.invalidate(jobId, updatedContextJson);
        }
        if (policyEvaluationService != null) {
            policyEvaluationService.processPolicyEvaluationContext(jobId, updatedContextJson);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeContextMaps(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object patchVal = entry.getValue();
            if (patchVal instanceof Map<?, ?> patchMap && target.get(key) instanceof Map<?, ?> targetMap) {
                Map<String, Object> mergedSubMap = new LinkedHashMap<>((Map<String, Object>) targetMap);
                mergedSubMap.putAll((Map<String, Object>) patchMap);
                target.put(key, mergedSubMap);
            } else {
                target.put(key, patchVal);
            }
        }
    }

    private void clearLease(Step step) {
        step.setLeaseOwner(null);
        step.setLeaseUntil(null);
        stepRepository.save(step);
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        return gunzip(compressed, 15 * 1024 * 1024);
    }

    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        if (compressed == null) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzis.read(buffer)) != -1) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new IOException("Uncompressed spool payload exceeds maximum size of " + maxUncompressedBytes + " bytes");
                }
                baos.write(buffer, 0, read);
            }
        }
        return baos.toByteArray();
    }
}
