package io.terrakube.api.plugin.scheduler.spool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.context.ContextUnavailableException;
import io.terrakube.api.plugin.context.ContextWriteService;
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

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class SpoolRecoveryService {

    private final StepRepository stepRepository;
    private final JobRepository jobRepository;
    private final StorageTypeService storageTypeService;
    private final ObjectMapper objectMapper;
    private final SpoolRecoveryProperties properties;
    private final ContextWriteService contextWriteService;
    private final PolicyEvaluationService policyEvaluationService;
    private final SpoolRecoveryMetrics metrics;

    @Autowired
    public SpoolRecoveryService(
            StepRepository stepRepository,
            JobRepository jobRepository,
            StorageTypeService storageTypeService,
            ObjectMapper objectMapper,
            SpoolRecoveryProperties properties,
            ContextWriteService contextWriteService,
            PolicyEvaluationService policyEvaluationService,
            SpoolRecoveryMetrics metrics) {
        this.stepRepository = stepRepository;
        this.jobRepository = jobRepository;
        this.storageTypeService = storageTypeService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.contextWriteService = contextWriteService;
        this.policyEvaluationService = policyEvaluationService;
        this.metrics = metrics;
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
            step.setLeaseOwner(workerId);
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
        String leaseOwner = step.getLeaseOwner();
        Job job = step.getJob();
        if (job == null && step.getId() != null) {
            Optional<Step> freshStep = stepRepository.findById(step.getId());
            if (freshStep.isPresent()) {
                step = freshStep.get();
                if (leaseOwner != null) {
                    step.setLeaseOwner(leaseOwner);
                }
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
            int updated = stepRepository.updateDeliveryState(step.getId(), DeliveryState.EXPIRED, leaseOwner);
            if (updated == 0 && leaseOwner != null) {
                log.warn("Lost lease while marking step {} expired", stepId);
                if (metrics != null) {
                    metrics.leaseLost();
                }
            }
            if (metrics != null) {
                metrics.stepExpired();
            }
            for (String phase : List.of("plan", "apply")) {
                storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
            }
            return true;
        }

        boolean anyRecovered = false;
        boolean anyCorruptedOrInvalid = false;
        for (String phase : List.of("plan", "apply")) {
            byte[] spooledBytes = storageTypeService.getSpooledContextPatch(orgId, jobId, stepId, phase);
            if (spooledBytes != null && spooledBytes.length > 0) {
                if (spooledBytes.length > properties.getMaxCompressedBytes()) {
                    log.error("Spooled payload for step {} job {} phase {} exceeds max compressed bytes ({} > {}). Deleting corrupted spool.",
                            stepId, jobId, phase, spooledBytes.length, properties.getMaxCompressedBytes());
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                    anyCorruptedOrInvalid = true;
                    if (metrics != null) {
                        metrics.spoolCorrupted();
                    }
                    continue;
                }

                Map<String, Object> payload;
                try {
                    byte[] uncompressed = SpoolCompressionUtil.gunzip(spooledBytes, properties.getMaxUncompressedBytes());
                    payload = objectMapper.readValue(uncompressed, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.error("Failed to decompress or parse spooled patch for step {} job {} phase {}: {}. Deleting corrupted spool.",
                            stepId, jobId, phase, e.getMessage(), e);
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                    anyCorruptedOrInvalid = true;
                    if (metrics != null) {
                        metrics.spoolCorrupted();
                    }
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = (Map<String, Object>) payload.get("envelope");
                if (!validateEnvelope(envelope, orgId, jobId, stepId, phase)) {
                    log.error("Security envelope mismatch for step {} job {} phase {}. Expected org={}, job={}, step={}, phase={}, but got {}. Deleting invalid spool.",
                            stepId, jobId, phase, orgId, jobId, stepId, phase, envelope);
                    storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
                    anyCorruptedOrInvalid = true;
                    if (metrics != null) {
                        metrics.spoolCorrupted();
                    }
                    continue;
                }

                long deliveryRevision = extractEpoch(envelope.get("deliveryRevision"));
                if (deliveryRevision <= 0 && step.getDeliveryRevision() != null) {
                    deliveryRevision = step.getDeliveryRevision();
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> patch = (Map<String, Object>) payload.get("patch");
                if (patch != null && !patch.isEmpty()) {
                    boolean merged;
                    try {
                        merged = mergeAndSaveContext(job.getId(), deliveryRevision, patch);
                    } catch (Exception e) {
                        log.warn("Transient error applying spooled patch for step {} job {} phase {}: {}. Will retry next sweep.",
                                stepId, jobId, phase, e.getMessage(), e);
                        clearLease(step);
                        return false;
                    }
                    if (!merged) {
                        log.warn("Context lock busy for job {}, will retry step {} next sweep", jobId, stepId);
                        clearLease(step);
                        return false;
                    }
                    anyRecovered = true;
                    if (metrics != null) {
                        metrics.stepRecovered(phase);
                    }
                }
                storageTypeService.deleteSpooledContextPatch(orgId, jobId, stepId, phase);
            }
        }

        if (anyRecovered) {
            int updated = stepRepository.updateDeliveryState(step.getId(), DeliveryState.RECOVERED, leaseOwner);
            if (updated == 0 && leaseOwner != null) {
                log.warn("Lost lease while marking step {} recovered", stepId);
                if (metrics != null) {
                    metrics.leaseLost();
                }
            }
            log.info("Successfully recovered spooled structured output for step {} job {}", stepId, jobId);
            return true;
        } else if (anyCorruptedOrInvalid) {
            log.warn("Spooled output for step {} job {} was corrupted or invalid, marking EXPIRED", stepId, jobId);
            int updated = stepRepository.updateDeliveryState(step.getId(), DeliveryState.EXPIRED, leaseOwner);
            if (updated == 0 && leaseOwner != null) {
                log.warn("Lost lease while marking corrupted step {} expired", stepId);
                if (metrics != null) {
                    metrics.leaseLost();
                }
            }
            if (metrics != null) {
                metrics.stepExpired();
            }
            return false;
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

    public boolean mergeAndSaveContext(int jobId, Map<String, Object> patch) throws IOException {
        return mergeAndSaveContext(jobId, 0L, patch);
    }

    public boolean mergeAndSaveContext(int jobId, long deliveryRevision, Map<String, Object> patch) throws IOException {
        try {
            ContextWriteService.PatchResult result = contextWriteService.patchRecovery(jobId, deliveryRevision, patch);
            if (result.context() != null && policyEvaluationService != null) {
                policyEvaluationService.processPolicyEvaluationContext(jobId, result.context());
            }
            return true;
        } catch (ContextUnavailableException e) {
            log.warn("Context lock busy for job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private long extractEpoch(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private void clearLease(Step step) {
        if (step != null && step.getId() != null) {
            int released = stepRepository.releaseLease(step.getId(), step.getLeaseOwner());
            if (released == 0 && step.getLeaseOwner() != null) {
                log.debug("Lease for step {} already expired or held by another owner", step.getId());
                if (metrics != null) {
                    metrics.leaseLost();
                }
            }
        }
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        return SpoolCompressionUtil.gunzip(compressed);
    }

    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        return SpoolCompressionUtil.gunzip(compressed, maxUncompressedBytes);
    }
}
