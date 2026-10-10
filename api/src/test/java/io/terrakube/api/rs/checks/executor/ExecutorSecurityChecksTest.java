package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.terrakube.api.repository.WorkspaceRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutorSecurityChecksTest {

    @Mock
    private RequestScope requestScope;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private UUID orgId;
    private UUID wsId;
    private int jobId;
    private UUID stepId;

    private Organization organization;
    private Workspace workspace;
    private Job job;
    private Step step;
    private History history;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        wsId = UUID.randomUUID();
        jobId = 42;
        stepId = UUID.randomUUID();

        organization = new Organization();
        organization.setId(orgId);

        workspace = new Workspace();
        workspace.setId(wsId);
        workspace.setOrganization(organization);

        job = new Job();
        job.setId(jobId);
        job.setWorkspace(workspace);
        job.setOrganization(organization);

        step = new Step();
        step.setId(stepId);
        step.setJob(job);

        history = new History();
        history.setId(UUID.randomUUID());
        history.setWorkspace(workspace);
    }

    private RequestScope mockScopeWithClaims(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(c -> c.putAll(claims))
                .build();
        User elideUser = new User(new JwtAuthenticationToken(jwt));
        when(requestScope.getUser()).thenReturn(elideUser);
        return requestScope;
    }

    // --- ExecutorReadWorkspace ---

    @Test
    void testExecutorReadWorkspace_MatchingClaims_Allowed() {
        ExecutorReadWorkspace check = new ExecutorReadWorkspace();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadWorkspace_MismatchedWorkspaceId_Denied() {
        ExecutorReadWorkspace check = new ExecutorReadWorkspace();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadWorkspace_MismatchedOrgId_Denied() {
        ExecutorReadWorkspace check = new ExecutorReadWorkspace();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", UUID.randomUUID().toString(),
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadWorkspace_NonInternalIssuer_Denied() {
        ExecutorReadWorkspace check = new ExecutorReadWorkspace();
        mockScopeWithClaims(Map.of(
                "iss", "DexOidc",
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }

    // --- ExecutorReadJob ---

    @Test
    void testExecutorReadJob_MatchingClaims_Allowed() {
        ExecutorReadJob check = new ExecutorReadJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId)
        ));

        assertTrue(check.ok(job, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadJob_MismatchedJobId_Denied() {
        ExecutorReadJob check = new ExecutorReadJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", "999"
        ));

        assertFalse(check.ok(job, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadJob_MismatchedWorkspaceId_Denied() {
        ExecutorReadJob check = new ExecutorReadJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString(),
                "jobId", String.valueOf(jobId)
        ));

        assertFalse(check.ok(job, requestScope, Optional.empty()));
    }

    // --- ExecutorManageJob ---

    @Test
    void testExecutorManageJob_MatchingClaims_Allowed() {
        ExecutorManageJob check = new ExecutorManageJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId)
        ));

        assertTrue(check.ok(job, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageJob_MissingJobIdClaim_Denied() {
        ExecutorManageJob check = new ExecutorManageJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(job, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageJob_MismatchedJobId_Denied() {
        ExecutorManageJob check = new ExecutorManageJob();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", "999"
        ));

        assertFalse(check.ok(job, requestScope, Optional.empty()));
    }

    // --- ExecutorReadJobStep ---

    @Test
    void testExecutorReadJobStep_MatchingStepId_Allowed() {
        ExecutorReadJobStep check = new ExecutorReadJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "stepId", stepId.toString()
        ));

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadJobStep_MatchingJobId_Allowed() {
        ExecutorReadJobStep check = new ExecutorReadJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId)
        ));

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadJobStep_MismatchedStepAndJobId_Denied() {
        ExecutorReadJobStep check = new ExecutorReadJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "stepId", UUID.randomUUID().toString(),
                "jobId", "999"
        ));

        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    // --- ExecutorManageJobStep ---

    @Test
    void testExecutorManageJobStep_MatchingClaims_Allowed() {
        ExecutorManageJobStep check = new ExecutorManageJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId),
                "stepId", stepId.toString()
        ));

        assertTrue(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageJobStep_MissingStepId_Denied() {
        ExecutorManageJobStep check = new ExecutorManageJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId)
        ));

        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageJobStep_MismatchedStepId_Denied() {
        ExecutorManageJobStep check = new ExecutorManageJobStep();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", String.valueOf(jobId),
                "stepId", UUID.randomUUID().toString()
        ));

        assertFalse(check.ok(step, requestScope, Optional.empty()));
    }

    // --- ExecutorReadHistory ---

    @Test
    void testExecutorReadHistory_MatchingWorkspaceId_Allowed() {
        ExecutorReadHistory check = new ExecutorReadHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertTrue(check.ok(history, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadHistory_MismatchedWorkspaceId_Denied() {
        ExecutorReadHistory check = new ExecutorReadHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertFalse(check.ok(history, requestScope, Optional.empty()));
    }

    // --- ExecutorReadOrganization ---

    @Test
    void testExecutorReadOrganization_MatchingOrgIdClaim_Allowed() {
        ExecutorReadOrganization check = new ExecutorReadOrganization();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(check.ok(organization, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadOrganization_MismatchedOrgIdClaim_Denied() {
        ExecutorReadOrganization check = new ExecutorReadOrganization();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", UUID.randomUUID().toString(),
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(organization, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadOrganization_WorkspaceLookupMatching_Allowed() {
        ExecutorReadOrganization check = new ExecutorReadOrganization();
        check.workspaceRepository = workspaceRepository;
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertTrue(check.ok(organization, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorReadOrganization_WorkspaceLookupMismatched_Denied() {
        ExecutorReadOrganization check = new ExecutorReadOrganization();
        check.workspaceRepository = workspaceRepository;

        Organization otherOrg = new Organization();
        otherOrg.setId(UUID.randomUUID());
        Workspace otherWs = new Workspace();
        otherWs.setId(wsId);
        otherWs.setOrganization(otherOrg);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(otherWs));

        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(organization, requestScope, Optional.empty()));
    }

    // --- ExecutorManageHistory ---

    @Test
    void testExecutorManageHistory_MatchingClaims_Allowed() {
        history.setJobReference("42");
        ExecutorManageHistory check = new ExecutorManageHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString(),
                "jobId", "42"
        ));

        assertTrue(check.ok(history, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageHistory_MismatchedWorkspaceId_Denied() {
        ExecutorManageHistory check = new ExecutorManageHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertFalse(check.ok(history, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageHistory_MismatchedOrganizationId_Denied() {
        ExecutorManageHistory check = new ExecutorManageHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", UUID.randomUUID().toString(),
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(history, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageHistory_MismatchedJobId_Denied() {
        history.setJobReference("42");
        ExecutorManageHistory check = new ExecutorManageHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString(),
                "jobId", "99"
        ));

        assertFalse(check.ok(history, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageHistory_NonInternalIssuer_Denied() {
        ExecutorManageHistory check = new ExecutorManageHistory();
        mockScopeWithClaims(Map.of(
                "iss", "OtherIssuer",
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(history, requestScope, Optional.empty()));
    }

    // --- ExecutorManageWorkspaceHistory ---

    @Test
    void testExecutorManageWorkspaceHistory_MatchingClaims_Allowed() {
        ExecutorManageWorkspaceHistory check = new ExecutorManageWorkspaceHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageWorkspaceHistory_MismatchedWorkspaceId_Denied() {
        ExecutorManageWorkspaceHistory check = new ExecutorManageWorkspaceHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", UUID.randomUUID().toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageWorkspaceHistory_MismatchedOrganizationId_Denied() {
        ExecutorManageWorkspaceHistory check = new ExecutorManageWorkspaceHistory();
        mockScopeWithClaims(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", UUID.randomUUID().toString(),
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }

    @Test
    void testExecutorManageWorkspaceHistory_NonInternalIssuer_Denied() {
        ExecutorManageWorkspaceHistory check = new ExecutorManageWorkspaceHistory();
        mockScopeWithClaims(Map.of(
                "iss", "OtherIssuer",
                "workspaceId", wsId.toString()
        ));

        assertFalse(check.ok(workspace, requestScope, Optional.empty()));
    }
}
