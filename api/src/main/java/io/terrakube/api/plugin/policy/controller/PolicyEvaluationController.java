package io.terrakube.api.plugin.policy.controller;

import io.terrakube.api.plugin.scheduler.policy.PolicyDriftEvaluationJob;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/policy/v1/organization/{organizationId}/workspace/{workspaceId}/evaluation")
public class PolicyEvaluationController {

    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;
    private final PolicyDriftEvaluationJob policyDriftEvaluationJob;

    public PolicyEvaluationController(
            WorkspaceRepository workspaceRepository,
            JobRepository jobRepository,
            PolicyDriftEvaluationJob policyDriftEvaluationJob) {
        this.workspaceRepository = workspaceRepository;
        this.jobRepository = jobRepository;
        this.policyDriftEvaluationJob = policyDriftEvaluationJob;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@policyWorkspaceAccessService.hasPolicyEvaluationPermission(authentication, #organizationId, #workspaceId)")
    public ResponseEntity<?> triggerPolicyEvaluation(
            @PathVariable("organizationId") String organizationId,
            @PathVariable("workspaceId") String workspaceId,
            Authentication authentication) {

        UUID orgUuid;
        UUID wsUuid;
        try {
            orgUuid = UUID.fromString(organizationId);
            wsUuid = UUID.fromString(workspaceId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(createErrorResponse("Invalid organizationId or workspaceId UUID format"));
        }

        Optional<Workspace> workspaceOptional = workspaceRepository.findById(wsUuid);
        if (workspaceOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse("Workspace not found"));
        }

        Workspace workspace = workspaceOptional.get();
        if (workspace.getOrganization() == null || !workspace.getOrganization().getId().equals(orgUuid)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse("Workspace does not belong to specified organization"));
        }

        if (workspace.isDeleted()) {
            return ResponseEntity.badRequest().body(createErrorResponse("Workspace is deleted"));
        }

        if (workspace.isLocked()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse("Workspace is currently locked"));
        }

        Optional<Job> lastCompletedJob = jobRepository.findFirstByWorkspaceAndAndStatusInOrderByIdDesc(
                workspace, List.of(JobStatus.completed)
        );

        if (lastCompletedJob.isEmpty() || lastCompletedJob.get().getTerraformPlan() == null || lastCompletedJob.get().getTerraformPlan().isBlank()) {
            return ResponseEntity.badRequest().body(createErrorResponse("Workspace has no completed runs with a valid Terraform plan to evaluate"));
        }

        String username = authentication != null ? authentication.getName() : "UI";
        Job dispatchedJob = policyDriftEvaluationJob.dispatchPolicyEvaluationJob(
                workspace,
                lastCompletedJob.get().getTerraformPlan(),
                username,
                JobVia.UI.getValue()
        );

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", String.valueOf(dispatchedJob.getId()));
        response.put("status", dispatchedJob.getStatus().name());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private Map<String, String> createErrorResponse(String message) {
        return Map.of("message", message);
    }
}
