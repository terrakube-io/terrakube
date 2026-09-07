package io.terrakube.api.plugin.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        policyEvaluationService = new PolicyEvaluationService(
                policyEvaluationRepository,
                policySetRepository,
                stepRepository,
                workspaceRepository,
                jobRepository,
                storageTypeService,
                policyNotificationService,
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
        org.setId(UUID.randomUUID());
        org.setName("test-org");
        job.setOrganization(org);

        when(jobRepository.findById(200)).thenReturn(Optional.of(job));
        when(stepRepository.findByJobId(200)).thenReturn(List.of(step));
        when(policyEvaluationRepository.findByJobAndStep(job, step)).thenReturn(Optional.empty());
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of());

        String json = "{\n" +
                "  \"policyEvaluation\": {\n" +
                "    \"status\": \"FAILED\",\n" +
                "    \"passedRules\": 2,\n" +
                "    \"warningRules\": 1,\n" +
                "    \"softMandatoryViolations\": 0,\n" +
                "    \"hardMandatoryViolations\": 1,\n" +
                "    \"results\": [{\"rule\": \"tag_check\", \"violation\": \"missing cost_center\"}]\n" +
                "  }\n" +
                "}";

        policyEvaluationService.processPolicyEvaluationContext(200, json);

        ArgumentCaptor<PolicyEvaluation> evalCaptor = ArgumentCaptor.forClass(PolicyEvaluation.class);
        verify(policyEvaluationRepository).save(evalCaptor.capture());
        PolicyEvaluation saved = evalCaptor.getValue();
        assertEquals(PolicyEvaluationStatus.FAILED, saved.getStatus());
        assertEquals(1, saved.getHardMandatoryViolations());

        ArgumentCaptor<Workspace> wsCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(wsCaptor.capture());
        assertEquals(PolicyComplianceStatus.NON_COMPLIANT, wsCaptor.getValue().getPolicyComplianceStatus());

        verify(policyNotificationService).sendPolicyViolationNotification(eq(job), any(), any());
    }
}
