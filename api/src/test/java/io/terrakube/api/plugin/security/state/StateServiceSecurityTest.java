package io.terrakube.api.plugin.security.state;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class StateServiceSecurityTest {

    private StateService stateService;
    private WorkspaceRepository workspaceRepository;
    private io.terrakube.api.repository.TeamRepository teamRepository;
    private io.terrakube.api.plugin.security.rbac.RbacService rbacService;
    private UUID orgId;
    private UUID wsId;

    @BeforeEach
    void setUp() {
        stateService = new StateService();
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        teamRepository = Mockito.mock(io.terrakube.api.repository.TeamRepository.class);
        rbacService = Mockito.mock(io.terrakube.api.plugin.security.rbac.RbacService.class);

        ReflectionTestUtils.setField(stateService, "workspaceRepository", workspaceRepository);
        ReflectionTestUtils.setField(stateService, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(stateService, "rbacService", rbacService);

        orgId = UUID.randomUUID();
        wsId = UUID.randomUUID();
    }

    @Test
    void testManageStateOwnWorkspaceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateDifferentWorkspaceReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        UUID otherWsId = UUID.randomUUID();
        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), otherWsId.toString()));
    }

    @Test
    void testManageStateDifferentOrganizationReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        UUID otherOrgId = UUID.randomUUID();
        assertFalse(stateService.hasManageStatePermission(jwt, otherOrgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateExecutorServiceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (EXECUTOR)"
        ));

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUnknownInternalFailsClosed() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "sub", "TerrakubeInternal (TOKEN)"
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateOwnWorkspaceReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", wsId.toString()
        ));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceGlobalSharingSameOrgReturnsTrue() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(true);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceGlobalSharingDiffOrgReturnsFalse() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();
        UUID otherOrgId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization targetOrg = new Organization();
        targetOrg.setId(otherOrgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(targetOrg);
        targetWs.setGlobalRemoteState(true);

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertFalse(stateService.hasReadStatePermission(jwt, otherOrgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceExplicitSharedIdsReturnsTrue() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID() + "," + consumerWsId + "," + UUID.randomUUID());

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testReadStateCrossWorkspaceNotSharedReturnsFalse() {
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        Organization org = new Organization();
        org.setId(orgId);

        Workspace targetWs = new Workspace();
        targetWs.setId(targetWsId);
        targetWs.setOrganization(org);
        targetWs.setGlobalRemoteState(false);
        targetWs.setSharedIds(UUID.randomUUID().toString());

        when(workspaceRepository.findById(targetWsId)).thenReturn(Optional.of(targetWs));

        assertFalse(stateService.hasReadStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testCrossWorkspaceConsumerCannotManageOrRollbackTargetWorkspace() {
        // Even if target workspace shares state globally, consumer token must NEVER have manage/rollback permission
        UUID consumerWsId = wsId;
        UUID targetWsId = UUID.randomUUID();

        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "organizationId", orgId.toString(),
                "workspaceId", consumerWsId.toString()
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), targetWsId.toString()));
    }

    @Test
    void testManageStateMissingOrgIdReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateMissingOrgIdReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "TerrakubeInternal",
                "workspaceId", wsId.toString()
        ));

        assertFalse(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUserTokenWithManageStatePermissionReturnsTrue() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(true);

        assertTrue(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testManageStateUserTokenWithoutManageStatePermissionReturnsFalse() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(false);
        when(workspaceRepository.findById(wsId)).thenReturn(Optional.empty());

        assertFalse(stateService.hasManageStatePermission(jwt, orgId.toString(), wsId.toString()));
    }

    @Test
    void testReadStateUserTokenDelegatesToManageState() {
        JwtAuthenticationToken jwt = Mockito.mock(JwtAuthenticationToken.class);
        when(jwt.getTokenAttributes()).thenReturn(Map.of(
                "iss", "https://dex.example.com",
                "groups", java.util.List.of("dev-team")
        ));

        io.terrakube.api.rs.team.Team team = new io.terrakube.api.rs.team.Team();
        when(teamRepository.findAllByOrganizationIdAndNameIn(orgId, java.util.List.of("dev-team")))
                .thenReturn(java.util.List.of(team));
        when(rbacService.canManageState(team)).thenReturn(true);

        assertTrue(stateService.hasReadStatePermission(jwt, orgId.toString(), wsId.toString()));
    }
}
