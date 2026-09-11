package io.terrakube.api.plugin.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.PolicySetRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.policy.PolicyComplianceStatus;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class PolicyEvaluationService {

    private final PolicyEvaluationRepository policyEvaluationRepository;
    private final PolicySetRepository policySetRepository;
    private final StepRepository stepRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;
    private final StorageTypeService storageTypeService;
    private final PolicyNotificationService policyNotificationService;
    private final PolicyResolutionService policyResolutionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processPolicyEvaluationContext(int jobId, String contextJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(contextJson);
            if (!rootNode.has("policyEvaluation")) {
                return;
            }

            JsonNode evalNode = rootNode.get("policyEvaluation");
            log.info("Processing policy evaluation for Job {}", jobId);

            Optional<Job> jobOpt = jobRepository.findById(jobId);
            if (jobOpt.isEmpty()) {
                log.warn("Job {} not found for policy evaluation processing", jobId);
                return;
            }
            Job job = jobOpt.get();

            Step step = null;
            if (evalNode.has("stepId") && !evalNode.get("stepId").asText().isBlank()) {
                String stepIdStr = evalNode.get("stepId").asText();
                try {
                    Optional<Step> stepOpt = stepRepository.findById(UUID.fromString(stepIdStr));
                    if (stepOpt.isPresent()) {
                        step = stepOpt.get();
                    }
                } catch (Exception e) {
                    log.warn("Invalid stepId format: {}", stepIdStr);
                }
            }

            if (step == null) {
                List<Step> steps = stepRepository.findByJobId(jobId);
                if (steps.isEmpty()) {
                    log.warn("No steps found for Job {}", jobId);
                    return;
                }

                // Find current or relevant step
                step = steps.get(0);
                for (Step s : steps) {
                    if (s.getName() != null && s.getName().toLowerCase().contains("plan")) {
                        step = s;
                        break;
                    }
                }
            }

            String statusStr = evalNode.has("status") ? evalNode.get("status").asText() : "PASSED";
            PolicyEvaluationStatus evalStatus;
            try {
                evalStatus = PolicyEvaluationStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                evalStatus = PolicyEvaluationStatus.FAILED;
            }

            int passedRules = evalNode.has("passedRules") ? evalNode.get("passedRules").asInt() : 0;
            int warningRules = evalNode.has("warningRules") ? evalNode.get("warningRules").asInt() : 0;
            int softViolations = evalNode.has("softMandatoryViolations") ? evalNode.get("softMandatoryViolations").asInt() : 0;
            int hardViolations = evalNode.has("hardMandatoryViolations") ? evalNode.get("hardMandatoryViolations").asInt() : 0;
            int shadowHard = evalNode.has("shadowHardViolations") ? evalNode.get("shadowHardViolations").asInt() : 0;
            int shadowSoft = evalNode.has("shadowSoftViolations") ? evalNode.get("shadowSoftViolations").asInt() : 0;

            if (passedRules == 0 && warningRules == 0 && softViolations == 0 && hardViolations == 0 && evalNode.has("results") && evalNode.get("results").isArray()) {
                for (JsonNode resNode : evalNode.get("results")) {
                    passedRules += resNode.has("passedRules") ? resNode.get("passedRules").asInt() : 0;
                    warningRules += resNode.has("warningRules") ? resNode.get("warningRules").asInt() : 0;
                    softViolations += resNode.has("softMandatoryViolations") ? resNode.get("softMandatoryViolations").asInt() : 0;
                    hardViolations += resNode.has("hardMandatoryViolations") ? resNode.get("hardMandatoryViolations").asInt() : 0;
                    shadowHard += resNode.has("shadowHardViolations") ? resNode.get("shadowHardViolations").asInt() : 0;
                    shadowSoft += resNode.has("shadowSoftViolations") ? resNode.get("shadowSoftViolations").asInt() : 0;
                }
            }

            final Step finalStep = step;
            Optional<PolicyEvaluation> existingEval = policyEvaluationRepository.findByJobAndStep(job, finalStep);
            PolicyEvaluation policyEvaluation = existingEval.orElseGet(() -> {
                PolicyEvaluation pe = new PolicyEvaluation();
                pe.setJob(job);
                pe.setStep(finalStep);
                return pe;
            });

            // Offload full violations JSON to object storage on Day 1 (Gap 11.7)
            if (evalNode.has("results")) {
                String storageUri = String.format("policy-evaluations/%d/violations.json", jobId);
                try {
                    String resultsJson = objectMapper.writeValueAsString(evalNode.get("results"));
                    storageTypeService.uploadPolicyEvaluation(storageUri, resultsJson);
                    policyEvaluation.setStorageUri(storageUri);
                    log.info("Offloading policy violations JSON to object storage: {}", storageUri);
                } catch (Exception e) {
                    log.error("Failed to serialize or upload policy results JSON for Job {}: {}", jobId, e.getMessage());
                }
            }

            policyEvaluation.setStatus(evalStatus);
            policyEvaluation.setPassedRules(passedRules);
            policyEvaluation.setWarningRules(warningRules);
            policyEvaluation.setSoftMandatoryViolations(softViolations);
            policyEvaluation.setHardMandatoryViolations(hardViolations);
            policyEvaluation.setShadowHardViolations(shadowHard);
            policyEvaluation.setShadowSoftViolations(shadowSoft);

            policyEvaluationRepository.save(policyEvaluation);
            log.info("Saved PolicyEvaluation record {} for Job {}", policyEvaluation.getId(), jobId);

            // Update workspace compliance status (Gap 11.1)
            Workspace workspace = job.getWorkspace();
            if (workspace != null) {
                if (hardViolations > 0 || softViolations > 0) {
                    workspace.setPolicyComplianceStatus(PolicyComplianceStatus.NON_COMPLIANT);
                } else if (evalStatus == PolicyEvaluationStatus.EXEMPTED) {
                    workspace.setPolicyComplianceStatus(PolicyComplianceStatus.EXEMPTED);
                } else {
                    workspace.setPolicyComplianceStatus(PolicyComplianceStatus.COMPLIANT);
                }
                workspaceRepository.save(workspace);
                log.info("Updated Workspace {} policy compliance status to {}",
                        workspace.getName(), workspace.getPolicyComplianceStatus());
            }

            // Notification on violations (Gap 11.3 & Issue 3.2)
            if ((softViolations > 0 || hardViolations > 0) && job.getOrganization() != null) {
                List<PolicySet> targetPolicySets = resolveViolatedPolicySets(job, evalNode);
                if (!targetPolicySets.isEmpty()) {
                    String reason = String.format("Job %d produced %d hard violations and %d soft violations requiring review.",
                            jobId, hardViolations, softViolations);
                    policyNotificationService.sendPolicyViolationNotification(job, targetPolicySets, reason);
                } else {
                    log.info("No target PolicySets found to notify for violations in Job {}", jobId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to process policy evaluation context for Job {}: {}", jobId, e.getMessage(), e);
        }
    }

    private List<PolicySet> resolveViolatedPolicySets(Job job, JsonNode evalNode) {
        Set<UUID> violatedPolicySetIds = new LinkedHashSet<>();

        if (evalNode.has("results") && evalNode.get("results").isArray()) {
            for (JsonNode r : evalNode.get("results")) {
                int itemHard = r.has("hardMandatoryViolations") ? r.get("hardMandatoryViolations").asInt() : 0;
                int itemSoft = r.has("softMandatoryViolations") ? r.get("softMandatoryViolations").asInt() : 0;
                if ((itemHard > 0 || itemSoft > 0) && r.has("policySetId") && !r.get("policySetId").asText().isBlank()) {
                    try {
                        violatedPolicySetIds.add(UUID.fromString(r.get("policySetId").asText()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid policySetId in results: {}", r.get("policySetId").asText());
                    }
                }
            }
        }

        List<PolicySet> targetPolicySets = new ArrayList<>();
        if (!violatedPolicySetIds.isEmpty()) {
            for (UUID id : violatedPolicySetIds) {
                policySetRepository.findById(id).ifPresent(ps -> {
                    if (job.getOrganization() == null || (ps.getOrganization() != null && ps.getOrganization().getId().equals(job.getOrganization().getId()))) {
                        targetPolicySets.add(ps);
                    }
                });
            }
        } else if (policyResolutionService != null) {
            // Fallback: When explicit policySetIds are not present in results (e.g. legacy/summary payloads),
            // scope strictly to PolicySets attached to the workspace rather than the entire organization.
            List<PolicyContext> workspacePolicies = policyResolutionService.resolvePoliciesForJob(job);
            if (workspacePolicies != null) {
                for (PolicyContext pc : workspacePolicies) {
                    if (pc.getPolicyId() != null && !pc.getPolicyId().isBlank()) {
                        try {
                            UUID id = UUID.fromString(pc.getPolicyId());
                            policySetRepository.findById(id).ifPresent(ps -> {
                                if (!targetPolicySets.contains(ps)) {
                                    targetPolicySets.add(ps);
                                }
                            });
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid policyId in policyContext: {}", pc.getPolicyId());
                        }
                    }
                }
            }
        }

        return targetPolicySets;
    }
}
