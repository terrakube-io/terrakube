package io.terrakube.api.plugin.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.PolicySetRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.policy.PolicyComplianceStatus;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyEvaluationServiceTest {

    private JobRepository jobRepository;
    private StepRepository stepRepository;
    private PolicyEvaluationRepository policyEvaluationRepository;
    private WorkspaceRepository workspaceRepository;
    private StorageTypeService storageTypeService;
    private PolicyNotificationService policyNotificationService;
    private PolicySetRepository policySetRepository;
    private PolicyResolutionService policyResolutionService;
    private PolicyEvaluationService policyEvaluationService;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        stepRepository = Mockito.mock(StepRepository.class);
        policyEvaluationRepository = Mockito.mock(PolicyEvaluationRepository.class);
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        storageTypeService = Mockito.mock(StorageTypeService.class);
        policyNotificationService = Mockito.mock(PolicyNotificationService.class);
        policySetRepository = Mockito.mock(PolicySetRepository.class);
        policyResolutionService = Mockito.mock(PolicyResolutionService.class);

        policyEvaluationService = new PolicyEvaluationService(
                policyEvaluationRepository,
                policySetRepository,
                stepRepository,
                workspaceRepository,
                jobRepository,
                storageTypeService,
                policyNotificationService,
                policyResolutionService,
                new ObjectMapper()
        );
    }

    @Test
    void processesCompliantEvaluationSuccessfully() {
        Job job = new Job();
        job.setId(100);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        when(jobRepository.findById(100)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(100)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"PASSED\",\n" +
                "    \"passedRules\": 5,\n" +
                "    \"warningRules\": 0,\n" +
                "    \"softMandatoryViolations\": 0,\n" +
                "    \"hardMandatoryViolations\": 0\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(100, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertEquals(PolicyEvaluationStatus.PASSED, saved.getStatus());
        assertEquals(5, saved.getPassedRules());
        assertEquals(0, saved.getHardMandatoryViolations());

        ArgumentCaptor<Workspace> wsCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(wsCaptor.capture());
        assertEquals(PolicyComplianceStatus.COMPLIANT, wsCaptor.getValue().getPolicyComplianceStatus());
    }

    @Test
    void processesNonCompliantEvaluationAndTriggersNotification() {
        Job job = new Job();
        job.setId(200);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        Organization org = new Organization();
        UUID orgId = UUID.randomUUID();
        org.setId(orgId);
        org.setName("test-org");
        job.setOrganization(org);

        UUID violatedPolicySetId = UUID.randomUUID();
        PolicySet violatedPolicySet = new PolicySet();
        violatedPolicySet.setId(violatedPolicySetId);
        violatedPolicySet.setName("security-baseline");
        violatedPolicySet.setOrganization(org);

        UUID otherPolicySetId = UUID.randomUUID();
        PolicySet otherPolicySet = new PolicySet();
        otherPolicySet.setId(otherPolicySetId);
        otherPolicySet.setName("azure-guidelines");
        otherPolicySet.setOrganization(org);

        when(jobRepository.findById(200)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(200)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());
        when(policySetRepository.findById(violatedPolicySetId)).thenReturn(Optional.of(violatedPolicySet));
        when(policySetRepository.findById(otherPolicySetId)).thenReturn(Optional.of(otherPolicySet));
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of(violatedPolicySet, otherPolicySet));

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"FAILED\",\n" +
                "    \"passedRules\": 2,\n" +
                "    \"warningRules\": 1,\n" +
                "    \"softMandatoryViolations\": 0,\n" +
                "    \"hardMandatoryViolations\": 1,\n" +
                "    \"results\": [\n" +
                "      {\n" +
                "        \"policySetId\": \"" + violatedPolicySetId + "\",\n" +
                "        \"policySetName\": \"security-baseline\",\n" +
                "        \"hardMandatoryViolations\": 1,\n" +
                "        \"softMandatoryViolations\": 0,\n" +
                "        \"violations\": [{\"rule\": \"tag_check\", \"violation\": \"missing cost_center\"}]\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(200, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertEquals(PolicyEvaluationStatus.FAILED, saved.getStatus());
        assertEquals(1, saved.getHardMandatoryViolations());
        assertEquals("policy-evaluations/200/violations.json", saved.getStorageUri());

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageTypeService).uploadPolicyEvaluation(uriCaptor.capture(), contentCaptor.capture());
        assertEquals("policy-evaluations/200/violations.json", uriCaptor.getValue());
        assertTrue(contentCaptor.getValue().contains("tag_check"));

        ArgumentCaptor<Workspace> wsCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(wsCaptor.capture());
        assertEquals(PolicyComplianceStatus.NON_COMPLIANT, wsCaptor.getValue().getPolicyComplianceStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicySet>> policySetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(policyNotificationService).sendPolicyViolationNotification(eq(job), policySetsCaptor.capture(), any());
        List<PolicySet> notifiedPolicySets = policySetsCaptor.getValue();
        assertEquals(1, notifiedPolicySets.size());
        assertEquals(violatedPolicySetId, notifiedPolicySets.get(0).getId());
    }

    @Test
    void processesNonCompliantEvaluationDoesNotNotifyPolicySetsWithoutViolations() {
        Job job = new Job();
        job.setId(201);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("test-org");
        job.setOrganization(org);

        UUID violatedPolicySetId = UUID.randomUUID();
        PolicySet violatedPolicySet = new PolicySet();
        violatedPolicySet.setId(violatedPolicySetId);
        violatedPolicySet.setName("security-baseline");
        violatedPolicySet.setOrganization(org);

        UUID compliantPolicySetId = UUID.randomUUID();
        PolicySet compliantPolicySet = new PolicySet();
        compliantPolicySet.setId(compliantPolicySetId);
        compliantPolicySet.setName("pci-dss");
        compliantPolicySet.setOrganization(org);

        when(jobRepository.findById(201)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(201)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());
        when(policySetRepository.findById(violatedPolicySetId)).thenReturn(Optional.of(violatedPolicySet));
        when(policySetRepository.findById(compliantPolicySetId)).thenReturn(Optional.of(compliantPolicySet));

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"FAILED\",\n" +
                "    \"passedRules\": 10,\n" +
                "    \"warningRules\": 0,\n" +
                "    \"softMandatoryViolations\": 0,\n" +
                "    \"hardMandatoryViolations\": 1,\n" +
                "    \"results\": [\n" +
                "      {\n" +
                "        \"policySetId\": \"" + violatedPolicySetId + "\",\n" +
                "        \"policySetName\": \"security-baseline\",\n" +
                "        \"hardMandatoryViolations\": 1,\n" +
                "        \"softMandatoryViolations\": 0\n" +
                "      },\n" +
                "      {\n" +
                "        \"policySetId\": \"" + compliantPolicySetId + "\",\n" +
                "        \"policySetName\": \"pci-dss\",\n" +
                "        \"hardMandatoryViolations\": 0,\n" +
                "        \"softMandatoryViolations\": 0\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(201, json);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicySet>> policySetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(policyNotificationService).sendPolicyViolationNotification(eq(job), policySetsCaptor.capture(), any());
        List<PolicySet> notifiedPolicySets = policySetsCaptor.getValue();
        assertEquals(1, notifiedPolicySets.size());
        assertEquals(violatedPolicySetId, notifiedPolicySets.get(0).getId());
    }

    @Test
    void processesNonCompliantEvaluationFallsBackToWorkspaceAttachedPoliciesWhenResultsLackPolicySetId() {
        Job job = new Job();
        job.setId(202);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("test-org");
        job.setOrganization(org);

        UUID attachedPolicySetId = UUID.randomUUID();
        PolicySet attachedPolicySet = new PolicySet();
        attachedPolicySet.setId(attachedPolicySetId);
        attachedPolicySet.setName("workspace-attached-policy");
        attachedPolicySet.setOrganization(org);

        when(jobRepository.findById(202)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(202)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());
        when(policySetRepository.findById(attachedPolicySetId)).thenReturn(Optional.of(attachedPolicySet));

        PolicyContext pc = PolicyContext.builder()
                .policyId(attachedPolicySetId.toString())
                .policyName("workspace-attached-policy")
                .build();
        when(policyResolutionService.resolvePoliciesForJob(job)).thenReturn(List.of(pc));

        // Legacy/summary JSON where results lack policySetId
        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"FAILED\",\n" +
                "    \"passedRules\": 1,\n" +
                "    \"warningRules\": 0,\n" +
                "    \"softMandatoryViolations\": 1,\n" +
                "    \"hardMandatoryViolations\": 0,\n" +
                "    \"results\": [{\"rule\": \"tag_check\", \"violation\": \"missing tag\"}]\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(202, json);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PolicySet>> policySetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(policyNotificationService).sendPolicyViolationNotification(eq(job), policySetsCaptor.capture(), any());
        List<PolicySet> notifiedPolicySets = policySetsCaptor.getValue();
        assertEquals(1, notifiedPolicySets.size());
        assertEquals(attachedPolicySetId, notifiedPolicySets.get(0).getId());
    }

    @Test
    void processesEvaluationWithStepIdInPayload() {
        Job job = new Job();
        job.setId(300);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        UUID explicitStepId = UUID.randomUUID();
        Step step = new Step();
        step.setId(explicitStepId);
        step.setName("customStep");

        when(jobRepository.findById(300)).thenReturn(Optional.of(job));
        when(stepRepository.findById(explicitStepId)).thenReturn(Optional.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"stepId\": \"" + explicitStepId + "\",\n" +
                "    \"status\": \"WAITING_APPROVAL\",\n" +
                "    \"passedRules\": 1,\n" +
                "    \"warningRules\": 0,\n" +
                "    \"softMandatoryViolations\": 1,\n" +
                "    \"hardMandatoryViolations\": 0\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(300, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertEquals(PolicyEvaluationStatus.FAILED, saved.getStatus());
        assertEquals(explicitStepId, saved.getStep().getId());
        assertEquals(1, saved.getSoftMandatoryViolations());
    }

    @Test
    void aggregatesCountsFromResultsArrayWhenTopLevelCountsOmitted() {
        Job job = new Job();
        job.setId(400);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        when(jobRepository.findById(400)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(400)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"WAITING_APPROVAL\",\n" +
                "    \"results\": [\n" +
                "      {\n" +
                "        \"passedRules\": 3,\n" +
                "        \"warningRules\": 1,\n" +
                "        \"softMandatoryViolations\": 2,\n" +
                "        \"hardMandatoryViolations\": 0\n" +
                "      },\n" +
                "      {\n" +
                "        \"passedRules\": 2,\n" +
                "        \"warningRules\": 0,\n" +
                "        \"softMandatoryViolations\": 1,\n" +
                "        \"hardMandatoryViolations\": 0\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(400, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertEquals(5, saved.getPassedRules());
        assertEquals(1, saved.getWarningRules());
        assertEquals(3, saved.getSoftMandatoryViolations());
        assertEquals(0, saved.getHardMandatoryViolations());
    }

    @Test
    void processesEvaluationWithoutResultsDoesNotUploadToStorage() {
        Job job = new Job();
        job.setId(500);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        job.setWorkspace(workspace);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setName("terraformPlan");

        when(jobRepository.findById(500)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(500)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"PASSED\",\n" +
                "    \"passedRules\": 3,\n" +
                "    \"warningRules\": 0,\n" +
                "    \"softMandatoryViolations\": 0,\n" +
                "    \"hardMandatoryViolations\": 0\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(500, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertNull(saved.getStorageUri());
        verify(storageTypeService, never()).uploadPolicyEvaluation(anyString(), anyString());
    }
}
