package io.terrakube.api.plugin.policy.controller;

import io.terrakube.api.plugin.scheduler.policy.PolicyDriftEvaluationJob;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyEvaluationControllerTest {

    private WorkspaceRepository workspaceRepository;
    private JobRepository jobRepository;
    private PolicyDriftEvaluationJob policyDriftEvaluationJob;
    private PolicyEvaluationController controller;

    private UUID orgId;
    private UUID wsId;
    private Organization organization;
    private Workspace workspace;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        policyDriftEvaluationJob = Mockito.mock(PolicyDriftEvaluationJob.class);

        controller = new PolicyEvaluationController(
                workspaceRepository,
                jobRepository,
                policyDriftEvaluationJob
        );

        orgId = UUID.randomUUID();
        wsId = UUID.randomUUID();

        organization = new Organization();
        organization.setId(orgId);
        organization.setName("TestOrg");

        workspace = new Workspace();
        workspace.setId(wsId);
        workspace.setName("test-ws");
        workspace.setOrganization(organization);
        workspace.setDeleted(false);
        workspace.setLocked(false);

        authentication = Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@example.com");
    }

    @Test
    void testTriggerPolicyEvaluation_Success() {
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        Job completedJob = new Job();
        completedJob.setId(100);
        completedJob.setTerraformPlan("http://storage/plan.json");
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(workspace, List.of(JobStatus.completed)))
                .thenReturn(Optional.of(completedJob));

        Job dispatchedJob = new Job();
        dispatchedJob.setId(200);
        dispatchedJob.setStatus(JobStatus.pending);
        when(policyDriftEvaluationJob.dispatchPolicyEvaluationJob(
                eq(workspace),
                eq("http://storage/plan.json"),
                eq("user@example.com"),
                eq(JobVia.UI.getValue())
        )).thenReturn(dispatchedJob);

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("200", body.get("jobId"));
        assertEquals("pending", body.get("status"));

        verify(policyDriftEvaluationJob).dispatchPolicyEvaluationJob(
                workspace, "http://storage/plan.json", "user@example.com", JobVia.UI.getValue()
        );
    }

    @Test
    void testTriggerPolicyEvaluation_InvalidUuid() {
        ResponseEntity<?> response = controller.triggerPolicyEvaluation("invalid-org", wsId.toString(), authentication);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testTriggerPolicyEvaluation_WorkspaceNotFound() {
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testTriggerPolicyEvaluation_WorkspaceDifferentOrg() {
        Organization otherOrg = new Organization();
        otherOrg.setId(UUID.randomUUID());
        workspace.setOrganization(otherOrg);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void testTriggerPolicyEvaluation_WorkspaceDeleted() {
        workspace.setDeleted(true);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Workspace is deleted", body.get("message"));
    }

    @Test
    void testTriggerPolicyEvaluation_WorkspaceLocked() {
        workspace.setLocked(true);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Workspace is currently locked", body.get("message"));
    }

    @Test
    void testTriggerPolicyEvaluation_NoCompletedPlan() {
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(jobRepository.findFirstByWorkspaceAndStatusInOrderByIdDesc(workspace, List.of(JobStatus.completed)))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.triggerPolicyEvaluation(orgId.toString(), wsId.toString(), authentication);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Workspace has no completed runs with a valid Terraform plan to evaluate", body.get("message"));
    }
}
