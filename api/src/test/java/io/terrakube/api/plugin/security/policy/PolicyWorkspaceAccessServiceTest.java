package io.terrakube.api.plugin.security.policy;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PolicyWorkspaceAccessServiceTest {

    private TeamRepository teamRepository;
    private WorkspaceRepository workspaceRepository;
    private RbacService rbacService;
    private TeamTokenService teamTokenService;
    private PolicyWorkspaceAccessService accessService;

    private UUID orgId;
    private UUID wsId;

    @BeforeEach
    void setUp() {
        teamRepository = Mockito.mock(TeamRepository.class);
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        rbacService = Mockito.mock(RbacService.class);
        teamTokenService = Mockito.mock(TeamTokenService.class);

        accessService = new PolicyWorkspaceAccessService(
                teamRepository,
                workspaceRepository,
                rbacService,
                teamTokenService,
                "TERRAKUBE_ADMIN"
        );

        orgId = UUID.randomUUID();
        wsId = UUID.randomUUID();
    }

    @Test
    void testNullAuthenticationReturnsFalse() {
        assertFalse(accessService.hasPolicyEvaluationPermission(null, orgId.toString(), wsId.toString()));
    }

    @Test
    void testInternalTokenReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "TerrakubeInternal"));

        assertTrue(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testSuperUserReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com"));
        when(teamTokenService.getCurrentGroups(jwt)).thenReturn(List.of("TERRAKUBE_ADMIN"));

        assertTrue(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testOrgLevelManageWorkspaceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com"));
        when(teamTokenService.getCurrentGroups(jwt)).thenReturn(List.of("dev-team"));

        Team team = new Team();
        team.setName("dev-team");
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, List.of("dev-team"))).thenReturn(List.of(team));
        when(rbacService.canManageWorkspace(team)).thenReturn(true);

        assertTrue(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testOrgLevelPlanJobReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com"));
        when(teamTokenService.getCurrentGroups(jwt)).thenReturn(List.of("plan-team"));

        Team team = new Team();
        team.setName("plan-team");
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, List.of("plan-team"))).thenReturn(List.of(team));
        when(rbacService.canPlanJob(team)).thenReturn(true);

        assertTrue(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testWorkspaceLevelAccessReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com"));
        when(teamTokenService.getCurrentGroups(jwt)).thenReturn(List.of("ws-operators"));

        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, List.of("ws-operators"))).thenReturn(List.of());

        Workspace ws = new Workspace();
        Access access = new Access();
        access.setName("ws-operators");
        ws.setAccess(List.of(access));

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(ws));
        when(rbacService.canPlanJob(access)).thenReturn(true);

        assertTrue(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testNoPermissionsReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of("iss", "https://idp.example.com"));
        when(teamTokenService.getCurrentGroups(jwt)).thenReturn(List.of("viewer-team"));

        Team team = new Team();
        team.setName("viewer-team");
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, List.of("viewer-team"))).thenReturn(List.of(team));
        when(rbacService.canManageWorkspace(team)).thenReturn(false);
        when(rbacService.canPlanJob(team)).thenReturn(false);

        Workspace ws = new Workspace();
        ws.setAccess(List.of());
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(ws));

        assertFalse(accessService.hasPolicyEvaluationPermission(jwt, orgId.toString(), wsId.toString()));
    }
}
