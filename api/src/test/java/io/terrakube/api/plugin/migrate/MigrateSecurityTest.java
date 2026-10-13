package io.terrakube.api.plugin.migrate;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrateSecurityTest {

    private WorkspaceRepository workspaceRepository;
    private OrganizationRepository organizationRepository;
    private TeamRepository teamRepository;
    private JobRepository jobRepository;
    private HistoryRepository historyRepository;
    private StorageTypeService storageTypeService;
    private RbacService rbacService;

    private MigrateAccessService migrateAccessService;
    private MigrateService migrateService;

    private static final String INSTANCE_OWNER = "TERRAKUBE_ADMIN";

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        teamRepository = mock(TeamRepository.class);
        jobRepository = mock(JobRepository.class);
        historyRepository = mock(HistoryRepository.class);
        storageTypeService = mock(StorageTypeService.class);
        rbacService = mock(RbacService.class);

        migrateAccessService = new MigrateAccessService(
                workspaceRepository,
                organizationRepository,
                teamRepository,
                rbacService,
                INSTANCE_OWNER
        );

        migrateService = new MigrateService(
                historyRepository,
                workspaceRepository,
                organizationRepository,
                jobRepository,
                storageTypeService
        );
    }

    private JwtAuthenticationToken createAuthToken(List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user@example.com")
                .claim("groups", groups)
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    @Test
    void testSuperuserAllowed() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        JwtAuthenticationToken auth = createAuthToken(List.of(INSTANCE_OWNER));
        assertTrue(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testCrossTenantUserDenied() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization sourceOrg = new Organization();
        sourceOrg.setId(UUID.randomUUID());
        workspace.setOrganization(sourceOrg);

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(sourceOrg.getId()), anyList())).thenReturn(Collections.emptyList());

        JwtAuthenticationToken auth = createAuthToken(List.of("SOME_TEAM"));
        assertFalse(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testSourceOrgAdminOnlyDenied() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization sourceOrg = new Organization();
        sourceOrg.setId(UUID.randomUUID());
        workspace.setOrganization(sourceOrg);

        Organization targetOrg = new Organization();
        targetOrg.setId(targetOrgId);

        Team sourceTeam = new Team();
        sourceTeam.setName("SOURCE_ADMINS");

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(organizationRepository.findById(targetOrgId)).thenReturn(Optional.of(targetOrg));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(sourceOrg.getId()), eq(List.of("SOURCE_ADMINS"))))
                .thenReturn(List.of(sourceTeam));
        when(rbacService.canManageWorkspace(sourceTeam)).thenReturn(true);

        // Target organization has no matching team for caller
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(targetOrgId), eq(List.of("SOURCE_ADMINS"))))
                .thenReturn(Collections.emptyList());

        JwtAuthenticationToken auth = createAuthToken(List.of("SOURCE_ADMINS"));
        assertFalse(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testTargetOrgAdminOnlyDenied() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization sourceOrg = new Organization();
        sourceOrg.setId(UUID.randomUUID());
        workspace.setOrganization(sourceOrg);

        Organization targetOrg = new Organization();
        targetOrg.setId(targetOrgId);

        Team targetTeam = new Team();
        targetTeam.setName("TARGET_ADMINS");

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(organizationRepository.findById(targetOrgId)).thenReturn(Optional.of(targetOrg));
        // Source org has no matching team for caller
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(sourceOrg.getId()), eq(List.of("TARGET_ADMINS"))))
                .thenReturn(Collections.emptyList());
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(targetOrgId), eq(List.of("TARGET_ADMINS"))))
                .thenReturn(List.of(targetTeam));
        when(rbacService.canManageWorkspace(targetTeam)).thenReturn(true);

        JwtAuthenticationToken auth = createAuthToken(List.of("TARGET_ADMINS"));
        assertFalse(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testDualOrgAdminAllowed() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization sourceOrg = new Organization();
        sourceOrg.setId(UUID.randomUUID());
        workspace.setOrganization(sourceOrg);

        Organization targetOrg = new Organization();
        targetOrg.setId(targetOrgId);

        Team sourceTeam = new Team();
        sourceTeam.setName("CORP_ADMINS");
        Team targetTeam = new Team();
        targetTeam.setName("CORP_ADMINS");

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(organizationRepository.findById(targetOrgId)).thenReturn(Optional.of(targetOrg));

        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(sourceOrg.getId()), eq(List.of("CORP_ADMINS"))))
                .thenReturn(List.of(sourceTeam));
        when(rbacService.canManageWorkspace(sourceTeam)).thenReturn(true);

        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(targetOrgId), eq(List.of("CORP_ADMINS"))))
                .thenReturn(List.of(targetTeam));
        when(rbacService.canManageWorkspace(targetTeam)).thenReturn(true);

        JwtAuthenticationToken auth = createAuthToken(List.of("CORP_ADMINS"));
        assertTrue(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testWorkspaceLevelAccessAndTargetOrgAllowed() {
        UUID wsId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization sourceOrg = new Organization();
        sourceOrg.setId(UUID.randomUUID());
        workspace.setOrganization(sourceOrg);

        Access wsAccess = new Access();
        wsAccess.setName("DEVOPS");
        workspace.setAccess(List.of(wsAccess));

        Organization targetOrg = new Organization();
        targetOrg.setId(targetOrgId);
        Team targetTeam = new Team();
        targetTeam.setName("DEVOPS");

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));
        when(organizationRepository.findById(targetOrgId)).thenReturn(Optional.of(targetOrg));

        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(sourceOrg.getId()), eq(List.of("DEVOPS"))))
                .thenReturn(Collections.emptyList());
        when(rbacService.canManageWorkspace(wsAccess)).thenReturn(true);

        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(targetOrgId), eq(List.of("DEVOPS"))))
                .thenReturn(List.of(targetTeam));
        when(rbacService.canManageWorkspace(targetTeam)).thenReturn(true);

        JwtAuthenticationToken auth = createAuthToken(List.of("DEVOPS"));
        assertTrue(migrateAccessService.hasMigrationPermission(auth, wsId.toString(), targetOrgId.toString()));
    }

    @Test
    void testSameOrgMigrationRejectedByService() {
        UUID wsId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Workspace workspace = new Workspace();
        Organization org = new Organization();
        org.setId(orgId);
        workspace.setOrganization(org);

        when(workspaceRepository.findById(wsId)).thenReturn(Optional.of(workspace));

        boolean result = migrateService.migrateWorkspace(wsId.toString(), orgId.toString());
        assertFalse(result, "Migration within the same organization must be rejected");
        verifyNoInteractions(storageTypeService);
    }
}
