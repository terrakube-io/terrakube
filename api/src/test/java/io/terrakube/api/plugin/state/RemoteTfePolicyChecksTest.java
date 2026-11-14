package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.policy.PolicyApprovalGuard;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.state.model.policy.PolicyCheckData;
import io.terrakube.api.plugin.state.model.policy.PolicyCheckList;
import io.terrakube.api.plugin.state.model.runs.RunsData;
import io.terrakube.api.plugin.state.model.workspace.WorkspaceData;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import io.terrakube.api.rs.policy.PolicyOverride;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemoteTfePolicyChecksTest {

    private JobRepository jobRepository;
    private PolicyEvaluationRepository policyEvaluationRepository;
    private PolicyOverrideRepository policyOverrideRepository;
    private PolicySetRepository policySetRepository;
    private TeamTokenService teamTokenService;
    private OrganizationRepository organizationRepository;
    private WorkspaceRepository workspaceRepository;
    private RemoteTfeService remoteTfeService;
    private RemoteTfeController remoteTfeController;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        policyEvaluationRepository = Mockito.mock(PolicyEvaluationRepository.class);
        policyOverrideRepository = Mockito.mock(PolicyOverrideRepository.class);
        policySetRepository = Mockito.mock(PolicySetRepository.class);
        teamTokenService = Mockito.mock(TeamTokenService.class);
        organizationRepository = Mockito.mock(OrganizationRepository.class);
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);

        ContentRepository contentRepository = Mockito.mock(ContentRepository.class);
        HistoryRepository historyRepository = Mockito.mock(HistoryRepository.class);
        TemplateRepository templateRepository = Mockito.mock(TemplateRepository.class);
        ScheduleJobService scheduleJobService = Mockito.mock(ScheduleJobService.class);
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        StepRepository stepRepository = Mockito.mock(StepRepository.class);
        RedisTemplate redisTemplate = Mockito.mock(RedisTemplate.class);
        TagRepository tagRepository = Mockito.mock(TagRepository.class);
        WorkspaceTagRepository workspaceTagRepository = Mockito.mock(WorkspaceTagRepository.class);
        ArchiveRepository archiveRepository = Mockito.mock(ArchiveRepository.class);
        AccessRepository accessRepository = Mockito.mock(AccessRepository.class);
        EncryptionService encryptionService = Mockito.mock(EncryptionService.class);
        AddressRepository addressRepository = Mockito.mock(AddressRepository.class);
        ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
        VariableRepository variableRepository = Mockito.mock(VariableRepository.class);
        GlobalVarRepository globalVarRepository = Mockito.mock(GlobalVarRepository.class);
        RbacService rbacService = Mockito.mock(RbacService.class);
        JobNotificationTrigger jobNotificationTrigger = Mockito.mock(JobNotificationTrigger.class);

        remoteTfeService = new RemoteTfeService(
                jobRepository, contentRepository, organizationRepository, workspaceRepository,
                historyRepository, templateRepository, scheduleJobService, "localhost", storageTypeService,
                stepRepository, redisTemplate, 1, tagRepository, workspaceTagRepository, teamTokenService,
                archiveRepository, accessRepository, encryptionService, addressRepository, projectRepository,
                variableRepository, globalVarRepository, rbacService, jobNotificationTrigger
        );
        remoteTfeService.setPolicyApprovalGuard(Mockito.mock(PolicyApprovalGuard.class));
        remoteTfeService.setPolicyEvaluationRepository(policyEvaluationRepository);
        remoteTfeService.setPolicyOverrideRepository(policyOverrideRepository);
        remoteTfeService.setPolicySetRepository(policySetRepository);

        remoteTfeController = new RemoteTfeController(remoteTfeService);
    }

    private JwtAuthenticationToken createJwtToken(String email, List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .claim("iss", "https://auth.terrakube.io")
                .claim("email", email)
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void getRunIncludesPolicyChecksRelationship() {
        int runId = 101;
        Job job = new Job();
        job.setId(runId);
        job.setStatus(JobStatus.completed);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        UUID evalId = UUID.randomUUID();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.PASSED);
        evaluation.setPassedRules(4);

        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));
        when(jobRepository.getReferenceById(runId)).thenReturn(job);
        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        RunsData runData = remoteTfeService.getRun(runId, null, user);
        assertNotNull(runData);
        assertNotNull(runData.getData().getRelationships().getPolicyChecks());
        assertEquals(1, runData.getData().getRelationships().getPolicyChecks().getData().size());
        assertEquals(evalId.toString(), runData.getData().getRelationships().getPolicyChecks().getData().get(0).getId());
        assertEquals("policy-checks", runData.getData().getRelationships().getPolicyChecks().getData().get(0).getType());
    }

    @Test
    void getRunIncludesPolicyChecksPayloadWhenIncludeRequested() {
        int runId = 102;
        Job job = new Job();
        job.setId(runId);
        job.setStatus(JobStatus.completed);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        UUID evalId = UUID.randomUUID();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.PASSED);
        evaluation.setPassedRules(3);

        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));
        when(jobRepository.getReferenceById(runId)).thenReturn(job);
        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        RunsData runData = remoteTfeService.getRun(runId, "policy-checks", user);
        assertNotNull(runData);
        assertNotNull(runData.getIncluded());
        assertEquals(1, runData.getIncluded().size());
    }

    @Test
    void getRunPolicyChecksReturnsList() {
        int runId = 201;
        Job job = new Job();
        job.setId(runId);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(UUID.randomUUID());
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.PASSED);

        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));
        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        PolicyCheckList list = remoteTfeService.getRunPolicyChecks(runId, user);
        assertNotNull(list);
        assertEquals(1, list.getData().size());
        assertEquals("passed", list.getData().get(0).getAttributes().get("status"));
    }

    @Test
    void getPolicyCheckStatusHardFailed() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(300);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.FAILED);
        evaluation.setHardMandatoryViolations(2);
        evaluation.setSoftMandatoryViolations(0);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        PolicyCheckData data = remoteTfeService.getPolicyCheck(evalId, user);
        assertNotNull(data);
        assertEquals("hard_failed", data.getData().getAttributes().get("status"));
        Map<String, Object> actions = (Map<String, Object>) data.getData().getAttributes().get("actions");
        assertFalse((Boolean) actions.get("is-overridable"));
        Map<String, Object> permissions = (Map<String, Object>) data.getData().getAttributes().get("permissions");
        assertFalse((Boolean) permissions.get("can-override"));
    }

    @Test
    void getPolicyCheckStatusSoftFailedWithAuthorizedUser() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(301);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        job.setOrganization(org);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("developers");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.FAILED);
        evaluation.setHardMandatoryViolations(0);
        evaluation.setSoftMandatoryViolations(1);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setOverrideTeam("secops");

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of(policySet));

        JwtAuthenticationToken secopsUser = createJwtToken("alice@corp.com", List.of("secops"));
        when(teamTokenService.getCurrentGroups(secopsUser)).thenReturn(List.of("secops"));

        PolicyCheckData data = remoteTfeService.getPolicyCheck(evalId, secopsUser);
        assertNotNull(data);
        assertEquals("soft_failed", data.getData().getAttributes().get("status"));
        Map<String, Object> actions = (Map<String, Object>) data.getData().getAttributes().get("actions");
        assertTrue((Boolean) actions.get("is-overridable"));
        Map<String, Object> permissions = (Map<String, Object>) data.getData().getAttributes().get("permissions");
        assertTrue((Boolean) permissions.get("can-override"));
    }

    @Test
    void getPolicyCheckStatusSoftFailedWithUnauthorizedUser() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(302);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        job.setOrganization(org);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("developers");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.FAILED);
        evaluation.setHardMandatoryViolations(0);
        evaluation.setSoftMandatoryViolations(1);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setOverrideTeam("secops");

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of(policySet));

        JwtAuthenticationToken devUser = createJwtToken("bob@corp.com", List.of("developers"));
        when(teamTokenService.getCurrentGroups(devUser)).thenReturn(List.of("developers"));

        PolicyCheckData data = remoteTfeService.getPolicyCheck(evalId, devUser);
        assertNotNull(data);
        assertEquals("soft_failed", data.getData().getAttributes().get("status"));
        Map<String, Object> actions = (Map<String, Object>) data.getData().getAttributes().get("actions");
        assertTrue((Boolean) actions.get("is-overridable"));
        Map<String, Object> permissions = (Map<String, Object>) data.getData().getAttributes().get("permissions");
        assertFalse((Boolean) permissions.get("can-override"));
    }

    @Test
    void getPolicyCheckStatusOverridden() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(400);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.PASSED);
        evaluation.setHardMandatoryViolations(0);
        evaluation.setSoftMandatoryViolations(1);

        PolicyOverride override = new PolicyOverride();
        override.setId(UUID.randomUUID());
        override.setOverriddenBy("alice@corp.com");
        override.setOverriddenAt(new Date());
        override.setJustification("Approved emergency fix");
        evaluation.setOverride(override);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        PolicyCheckData data = remoteTfeService.getPolicyCheck(evalId, user);
        assertNotNull(data);
        assertEquals("overridden", data.getData().getAttributes().get("status"));
    }

    @Test
    void getPolicyCheckOutputFormatsAnsiReport() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(401);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setPassedRules(5);
        evaluation.setWarningRules(1);
        evaluation.setSoftMandatoryViolations(1);
        evaluation.setHardMandatoryViolations(0);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        String output = remoteTfeService.getPolicyCheckOutput(evalId, user);
        assertNotNull(output);
        assertTrue(output.contains("TERRAKUBE POLICY GUARDRAILS (OPA)"));
        assertTrue(output.contains("[PASSED]   5 policy rule(s) passed"));
        assertTrue(output.contains("[ADVISORY] 1 advisory warning(s) detected"));
        assertTrue(output.contains("[WARNING]  1 soft mandatory violation(s) requiring override approval"));
        assertTrue(output.contains("Overall Status: REQUIRES OVERRIDE APPROVAL"));
    }

    @Test
    void overridePolicyCheckSuccess() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(401);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        job.setOrganization(org);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("developers");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.FAILED);
        evaluation.setHardMandatoryViolations(0);
        evaluation.setSoftMandatoryViolations(1);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setOverrideTeam("secops");

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of(policySet));

        JwtAuthenticationToken secopsUser = createJwtToken("alice@corp.com", List.of("secops"));
        when(teamTokenService.getCurrentGroups(secopsUser)).thenReturn(List.of("secops"));

        PolicyCheckData result = remoteTfeService.overridePolicyCheck(evalId, "SecOps approval via CLI", secopsUser);
        assertNotNull(result);
        assertEquals("overridden", result.getData().getAttributes().get("status"));
        verify(policyOverrideRepository).save(any(PolicyOverride.class));
        verify(policyEvaluationRepository).save(evaluation);
        assertEquals(PolicyEvaluationStatus.PASSED, evaluation.getStatus());
        assertNotNull(evaluation.getOverride());
        assertEquals("alice@corp.com", evaluation.getOverride().getOverriddenBy());
    }

    @Test
    void overridePolicyCheckForbiddenForUnauthorizedUser() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(402);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        job.setOrganization(org);

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("developers");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.FAILED);
        evaluation.setHardMandatoryViolations(0);
        evaluation.setSoftMandatoryViolations(1);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setOverrideTeam("secops");

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        when(policySetRepository.findByOrganization(org)).thenReturn(List.of(policySet));

        JwtAuthenticationToken devUser = createJwtToken("bob@corp.com", List.of("devs"));
        when(teamTokenService.getCurrentGroups(devUser)).thenReturn(List.of("devs"));

        assertThrows(AccessDeniedException.class, () ->
                remoteTfeService.overridePolicyCheck(evalId, "Try override", devUser));
    }

    @Test
    void overridePolicyCheckRejectsHardMandatoryViolations() {
        UUID evalId = UUID.randomUUID();
        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setHardMandatoryViolations(1);
        evaluation.setSoftMandatoryViolations(0);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        assertThrows(IllegalArgumentException.class, () ->
                remoteTfeService.overridePolicyCheck(evalId, "Justification", null));
    }

    @Test
    void controllerEndpointsSupportPolchkPrefix() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(500);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setTeam(Collections.emptyList());
        workspace.setOrganization(org);
        Access access = new Access();
        access.setName("users");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);
        evaluation.setStatus(PolicyEvaluationStatus.PASSED);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        JwtAuthenticationToken user = createJwtToken("alice@corp.com", List.of("users"));
        when(teamTokenService.getCurrentGroups(user)).thenReturn(List.of("users"));

        ResponseEntity<PolicyCheckData> response = remoteTfeController.getPolicyCheck("polchk-" + evalId, user);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        ResponseEntity<String> outputResponse = remoteTfeController.getPolicyCheckOutput("polchk-" + evalId, user);
        assertEquals(HttpStatus.OK, outputResponse.getStatusCode());
    }

    @Test
    void updateWorkspaceReturnsNotFoundWhenWorkspaceDoesNotExist() {
        UUID workspaceId = UUID.randomUUID();
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.empty());

        WorkspaceData requestData = new WorkspaceData();
        ResponseEntity<WorkspaceData> response = remoteTfeController.updateWorkspace(
                workspaceId.toString(), requestData, null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getRun_unauthorizedUser_throwsAccessDeniedException() {
        int runId = 991;
        Job job = new Job();
        job.setId(runId);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Access access = new Access();
        access.setName("authorized-group");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));
        JwtAuthenticationToken unauthorizedUser = createJwtToken("eve@corp.com", List.of("unauthorized-group"));
        when(teamTokenService.getCurrentGroups(unauthorizedUser)).thenReturn(List.of("unauthorized-group"));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getRun(runId, null, unauthorizedUser));
    }

    @Test
    void getRun_nullUser_throwsAccessDeniedException() {
        int runId = 992;
        Job job = new Job();
        job.setId(runId);
        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getRun(runId, null, null));
    }

    @Test
    void getRunPolicyChecks_unauthorizedUser_throwsAccessDeniedException() {
        int runId = 993;
        Job job = new Job();
        job.setId(runId);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Access access = new Access();
        access.setName("authorized-group");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));
        JwtAuthenticationToken unauthorizedUser = createJwtToken("eve@corp.com", List.of("unauthorized-group"));
        when(teamTokenService.getCurrentGroups(unauthorizedUser)).thenReturn(List.of("unauthorized-group"));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getRunPolicyChecks(runId, unauthorizedUser));
    }

    @Test
    void getRunPolicyChecks_nullUser_throwsAccessDeniedException() {
        int runId = 994;
        Job job = new Job();
        job.setId(runId);
        when(jobRepository.findById(runId)).thenReturn(Optional.of(job));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getRunPolicyChecks(runId, null));
    }

    @Test
    void getPolicyCheck_unauthorizedUser_throwsAccessDeniedException() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(995);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Access access = new Access();
        access.setName("authorized-group");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        JwtAuthenticationToken unauthorizedUser = createJwtToken("eve@corp.com", List.of("unauthorized-group"));
        when(teamTokenService.getCurrentGroups(unauthorizedUser)).thenReturn(List.of("unauthorized-group"));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getPolicyCheck(evalId, unauthorizedUser));
    }

    @Test
    void getPolicyCheck_nullUser_throwsAccessDeniedException() {
        UUID evalId = UUID.randomUUID();
        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getPolicyCheck(evalId, null));
    }

    @Test
    void getPolicyCheckOutput_unauthorizedUser_throwsAccessDeniedException() {
        UUID evalId = UUID.randomUUID();
        Job job = new Job();
        job.setId(996);
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Access access = new Access();
        access.setName("authorized-group");
        workspace.setAccess(List.of(access));
        job.setWorkspace(workspace);

        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        evaluation.setJob(job);

        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));
        JwtAuthenticationToken unauthorizedUser = createJwtToken("eve@corp.com", List.of("unauthorized-group"));
        when(teamTokenService.getCurrentGroups(unauthorizedUser)).thenReturn(List.of("unauthorized-group"));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getPolicyCheckOutput(evalId, unauthorizedUser));
    }

    @Test
    void getPolicyCheckOutput_nullUser_throwsAccessDeniedException() {
        UUID evalId = UUID.randomUUID();
        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setId(evalId);
        when(policyEvaluationRepository.findById(evalId)).thenReturn(Optional.of(evaluation));

        assertThrows(AccessDeniedException.class, () -> remoteTfeService.getPolicyCheckOutput(evalId, null));
    }
}
