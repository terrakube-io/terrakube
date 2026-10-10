package io.terrakube.api.plugin.security.job;

import io.terrakube.api.plugin.security.rbac.RbacService;
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
    private RbacService rbacService;
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
        service = new JobLogAccessService(jobRepository, stepRepository, teamRepository, rbacService, instanceOwner);

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
    void testCheckAccess_TerrakubeInternalToken_ReturnsAllowed() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "TerrakubeInternal"));

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
    void testCanWriteContext_TerrakubeInternalToken_MatchingJobId_ReturnsTrue() {
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", String.valueOf(jobId)
        ));

        assertTrue(service.canWriteContext(jwt, jobId));
    }

    @Test
    void testCanWriteContext_TerrakubeInternalToken_MismatchedJobId_ReturnsFalse() {
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "jobId", "99999"
        ));

        assertFalse(service.canWriteContext(jwt, jobId));
    }
}
