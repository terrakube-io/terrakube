package io.terrakube.api.plugin.security.job;

import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLogAccessServiceTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private StepRepository stepRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private AuthenticatedUser authenticatedUser;
    @Mock
    private GroupService groupService;
    @Mock
    private JwtAuthenticationToken jwt;

    private JobLogAccessService service;
    private final String instanceOwner = "SUPER_ADMIN";
    private final UUID orgId = UUID.randomUUID();
    private final int jobId = 42;
    private final UUID stepId = UUID.randomUUID();

    private Organization organization;
    private Workspace workspace;
    private Job job;
    private Step step;

    @BeforeEach
    void setUp() {
        service = new JobLogAccessService(jobRepository, stepRepository, teamRepository, groupService, authenticatedUser, instanceOwner);
        // A regular token: the effective groups are the groups claim. Tests for federated tokens override it.
        lenient().when(groupService.getEffectiveGroups(any())).thenAnswer(invocation -> {
            Object claim = jwt.getTokenAttributes().get("groups");
            return claim instanceof List<?> list ? new LinkedHashSet<>(list.stream().map(Object::toString).toList()) : new LinkedHashSet<String>();
        });

        organization = new Organization();
        organization.setId(orgId);

        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setOrganization(organization);

        job = new Job();
        job.setId(jobId);
        job.setOrganization(organization);
        job.setWorkspace(workspace);

        step = new Step();
        step.setId(stepId);
        step.setJob(job);
    }

    // The token the executor daemon sends: no job and no workspace of its own.
    private Map<String, Object> executorServiceToken() {
        return Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (EXECUTOR)");
    }

    // The token of one step run: it says which job and workspace it is for.
    private Map<String, Object> runnerToken(String tokenJobId) {
        return Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (EXECUTOR)",
                "jobId", tokenJobId, "workspaceId", workspace.getId().toString());
    }

    @Test
    void testCheckAccess_NullOrInvalidAuthentication_ReturnsForbidden() {
        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN,
                service.checkAccess(null, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_InvalidJobIdFormat_ReturnsNotFound() {
        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, orgId.toString(), "not-a-number", stepId.toString()));
    }

    @Test
    void testCheckAccess_JobNotFound_ReturnsNotFound() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_MismatchedOrganizationId_ReturnsNotFound() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID otherOrgId = UUID.randomUUID();
        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, otherOrgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_InvalidStepIdFormat_ReturnsNotFound() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), "invalid-uuid"));
    }

    @Test
    void testCheckAccess_StepNotFound_ReturnsNotFound() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.empty());

        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_StepBelongsToDifferentJob_ReturnsNotFound() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Job otherJob = new Job();
        otherJob.setId(999);
        step.setJob(otherJob);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));

        assertEquals(JobLogAccessService.LogAccessResult.NOT_FOUND,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_ExecutorServiceToken_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(executorServiceToken());

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_InstanceOwner_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "Terrakube",
                "groups", List.of("SOME_TEAM", instanceOwner)
        ));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_OrgTeamMember_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "Terrakube",
                "groups", List.of("DEV_TEAM")
        ));

        Team team = new Team();
        team.setName("DEV_TEAM");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), eq(List.of("DEV_TEAM"))))
                .thenReturn(List.of(team));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_WorkspaceAccessMember_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "Terrakube",
                "groups", List.of("WS_DEV")
        ));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());

        Access wsAccess = new Access();
        wsAccess.setName("WS_DEV");
        workspace.setAccess(List.of(wsAccess));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_ProjectAccessMember_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "Terrakube",
                "groups", List.of("PROJ_DEV")
        ));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());

        Project project = new Project();
        ProjectAccess pa = new ProjectAccess();
        pa.setName("PROJ_DEV");
        project.setProjectAccess(List.of(pa));
        workspace.setProject(project);

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_UnauthorizedUser_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "Terrakube",
                "groups", List.of("UNRELATED_GROUP")
        ));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        workspace.setAccess(List.of());

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_TerrakubeInternalToken_MatchingJobId_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", String.valueOf(jobId),
                "workspaceId", workspace.getId().toString()
        ));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_TerrakubeInternalToken_MismatchedJobId_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", "99999",
                "workspaceId", workspace.getId().toString()
        ));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckJobAccess_TerrakubeInternalToken_MatchingJobId_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", String.valueOf(jobId),
                "workspaceId", workspace.getId().toString()
        ));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_TerrakubeInternalToken_MismatchedJobId_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", "99999",
                "workspaceId", workspace.getId().toString()
        ));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN,
                service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCanWriteContext_RunnerToken_MatchingJobId_ReturnsTrue() {
        when(jwt.getTokenAttributes()).thenReturn(runnerToken(String.valueOf(jobId)));

        assertTrue(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCanWriteContext_RunnerToken_MismatchedJobId_ReturnsFalse() {
        when(jwt.getTokenAttributes()).thenReturn(runnerToken("99999"));

        assertFalse(service.canWriteContext(jwt, jobId));
    }

    // A GitHub OIDC token has no groups claim. Its team comes from the federated credential it matches,
    // which GroupService adds to the effective groups.
    private void federatedToken(String... teamNames) {
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://token.actions.githubusercontent.com",
                "aud", "terrakube",
                "sub", "repo:acme/infra:environment:production"
        ));
        lenient().when(groupService.getEffectiveGroups(any())).thenReturn(new LinkedHashSet<>(List.of(teamNames)));
    }

    @Test
    void testCheckAccess_FederatedTeamInTheJobsOrganization_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        federatedToken("GITHUB_PLANNERS");
        Team team = new Team();
        team.setName("GITHUB_PLANNERS");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), eq(List.of("GITHUB_PLANNERS"))))
                .thenReturn(List.of(team));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_FederatedTeamWithoutATeamInTheJobsOrganization_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        federatedToken("GITHUB_PLANNERS");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        workspace.setAccess(List.of());

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckAccess_FederatedTeamWithWorkspaceAccess_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        federatedToken("GITHUB_PLANNERS");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        Access wsAccess = new Access();
        wsAccess.setName("GITHUB_PLANNERS");
        workspace.setAccess(List.of(wsAccess));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckJobAccess_FederatedTeamInTheJobsOrganization_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        federatedToken("GITHUB_PLANNERS");
        Team team = new Team();
        team.setName("GITHUB_PLANNERS");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), eq(List.of("GITHUB_PLANNERS"))))
                .thenReturn(List.of(team));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_FederatedTeamWithProjectAccess_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        federatedToken("GITHUB_PLANNERS");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        Project project = new Project();
        ProjectAccess pa = new ProjectAccess();
        pa.setName("GITHUB_PLANNERS");
        project.setProjectAccess(List.of(pa));
        workspace.setProject(project);

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_FederatedTeamWithNoAccessToTheJob_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        federatedToken("OTHER_TEAM");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        workspace.setAccess(List.of());

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_NoGroupsAndNoFederatedCredential_ReturnsForbidden() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://token.actions.githubusercontent.com"));
        when(groupService.getEffectiveGroups(any())).thenReturn(new LinkedHashSet<>());

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCanWriteContext_FederatedTeamIsNotAllowedToWrite() {
        federatedToken("GITHUB_PLANNERS", instanceOwner);

        assertFalse(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_InternalTokenDoesNotResolveGroups() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(runnerToken(String.valueOf(jobId)));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED, service.checkJobAccess(jwt, jobId));
        verify(groupService, never()).getEffectiveGroups(any());
    }

    // --- Internal tokens ---------------------------------------------------------------------------

    @Test
    void testCheckAccess_InternalTokenWithNoJobAndNoWorkspaceAndNoSubjectIsRefused() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "TerrakubeInternal"));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_RegistryServiceTokenIsRefused() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (REGISTRY)"));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
        assertFalse(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_LegacyInternalTokenIsRefused() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "TerrakubeInternal", "sub", "TerrakubeInternal (TOKEN)"));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
        assertFalse(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_RunnerTokenWithoutAJobIsRefused() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal", "workspaceId", workspace.getId().toString()));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
        assertFalse(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCheckJobAccess_RunnerTokenOfAnotherWorkspaceIsRefused() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal", "jobId", String.valueOf(jobId), "workspaceId", UUID.randomUUID().toString()));

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCanWriteContext_ExecutorServiceTokenIsAllowed() {
        when(jwt.getTokenAttributes()).thenReturn(executorServiceToken());

        assertTrue(service.canWriteContext(jwt, jobId));
    }

    // --- Instance owner ------------------------------------------------------------------------------

    @Test
    void testCheckAccess_InstanceOwnerByEmailIsAllowedForLogsToo() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "Terrakube", "email", instanceOwner));

        assertEquals(JobLogAccessService.LogAccessResult.ALLOWED,
                service.checkAccess(jwt, orgId.toString(), String.valueOf(jobId), stepId.toString()));
    }

    @Test
    void testCheckJobAccess_AFederatedTokenWithTheOwnersEmailIsNotTheOwner() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com", "email", instanceOwner));
        when(authenticatedUser.isFederatedAccount(any())).thenReturn(true);

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
    }

    @Test
    void testCanWriteContext_InstanceOwnerByEmailIsAllowed() {
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "Terrakube", "email", instanceOwner));

        assertTrue(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCanWriteContext_AFederatedTokenWithTheOwnersEmailIsRefused() {
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com", "email", instanceOwner));
        when(authenticatedUser.isFederatedAccount(any())).thenReturn(true);

        assertFalse(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCanWriteContext_AFederatedTokenWithTheOwnersGroupIsRefused() {
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com", "groups", List.of(instanceOwner)));
        when(authenticatedUser.isFederatedAccount(any())).thenReturn(true);

        assertFalse(service.canWriteContext(jwt, jobId));
    }

    // --- No groups at all ----------------------------------------------------------------------------

    @Test
    void testCheckJobAccess_NoEffectiveGroupsIsRefusedWithoutQueryingTeams() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://token.actions.githubusercontent.com", "sub", "repo:acme/infra"));
        when(groupService.getEffectiveGroups(any())).thenReturn(new LinkedHashSet<>());

        assertEquals(JobLogAccessService.LogAccessResult.FORBIDDEN, service.checkJobAccess(jwt, jobId));
        verify(teamRepository, never()).findAllByOrganizationIdAndNameIn(any(), any());
    }
}
