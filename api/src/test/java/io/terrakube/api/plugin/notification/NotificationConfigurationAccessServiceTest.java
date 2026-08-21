package io.terrakube.api.plugin.notification;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationConfigurationAccessServiceTest {

    TeamRepository teamRepository;
    NotificationConfigurationRepository notificationConfigurationRepository;
    OrganizationRepository organizationRepository;
    WorkspaceRepository workspaceRepository;
    RbacService rbacService;
    NotificationConfigurationAccessService subject;

    final UUID orgId = UUID.randomUUID();
    final UUID workspaceId = UUID.randomUUID();
    final UUID configId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        teamRepository = mock(TeamRepository.class);
        notificationConfigurationRepository = mock(NotificationConfigurationRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        rbacService = mock(RbacService.class);

        subject = new NotificationConfigurationAccessService(teamRepository, notificationConfigurationRepository,
                organizationRepository, workspaceRepository, rbacService);
    }

    private JwtAuthenticationToken authWithClaims(Map<String, Object> claims) {
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getTokenAttributes()).thenReturn(claims);
        return token;
    }

    private Organization org() {
        Organization organization = new Organization();
        organization.setId(orgId);
        return organization;
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrganization(org());
        return workspace;
    }

    private NotificationConfiguration orgScopedConfig() {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setId(configId);
        configuration.setOrganization(org());
        return configuration;
    }

    private NotificationConfiguration workspaceScopedConfig(Workspace workspace) {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setId(configId);
        configuration.setWorkspace(workspace);
        return configuration;
    }

    @Test
    void hasManagePermission_internalIssuerBypassesAllChecks() {
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "TerrakubeInternal"));

        assertThat(subject.hasManagePermission(token, configId.toString())).isTrue();
    }

    @Test
    void hasManagePermission_returnsFalseWhenConfigurationNotFound() {
        when(notificationConfigurationRepository.findById(configId)).thenReturn(Optional.empty());
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermission(token, configId.toString())).isFalse();
    }

    @Test
    void hasManagePermission_orgScoped_returnsTrueWhenTeamCanManageWorkspace() {
        when(notificationConfigurationRepository.findById(configId)).thenReturn(Optional.of(orgScopedConfig()));
        Team writeTeam = new Team();
        writeTeam.setRole("write");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(writeTeam));
        when(rbacService.canManageWorkspace(writeTeam)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermission(token, configId.toString())).isTrue();
    }

    @Test
    void hasManagePermission_workspaceScoped_returnsTrueViaWorkspaceLevelAccessGrant_whenNoOrgWideAccess() {
        Workspace workspace = workspace();
        Access limitedAccess = new Access();
        limitedAccess.setName("scoped-team");
        workspace.setAccess(List.of(limitedAccess));
        when(notificationConfigurationRepository.findById(configId))
                .thenReturn(Optional.of(workspaceScopedConfig(workspace)));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        when(rbacService.canManageWorkspace(limitedAccess)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("scoped-team")));

        assertThat(subject.hasManagePermission(token, configId.toString())).isTrue();
    }

    @Test
    void hasManagePermission_workspaceScoped_returnsTrueViaProjectLevelAccessGrant_whenNoOrgOrWorkspaceAccess() {
        Workspace workspace = workspace();
        workspace.setAccess(List.of());
        Project project = new Project();
        ProjectAccess projectAccess = new ProjectAccess();
        projectAccess.setName("project-team");
        project.setProjectAccess(List.of(projectAccess));
        workspace.setProject(project);
        when(notificationConfigurationRepository.findById(configId))
                .thenReturn(Optional.of(workspaceScopedConfig(workspace)));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());
        when(rbacService.canManageWorkspace(projectAccess)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("project-team")));

        assertThat(subject.hasManagePermission(token, configId.toString())).isTrue();
    }

    @Test
    void hasManagePermission_workspaceScoped_returnsFalseWhenNoTierGrantsAccess() {
        Workspace workspace = workspace();
        workspace.setAccess(List.of());
        when(notificationConfigurationRepository.findById(configId))
                .thenReturn(Optional.of(workspaceScopedConfig(workspace)));
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of());

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("unrelated-team")));

        assertThat(subject.hasManagePermission(token, configId.toString())).isFalse();
    }

    @Test
    void hasManagePermissionForOrganization_returnsFalseWhenOrganizationNotFound() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermissionForOrganization(token, orgId.toString())).isFalse();
    }

    @Test
    void hasManagePermissionForOrganization_returnsTrueWhenTeamCanManageWorkspace() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org()));
        Team writeTeam = new Team();
        writeTeam.setRole("write");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(writeTeam));
        when(rbacService.canManageWorkspace(writeTeam)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermissionForOrganization(token, orgId.toString())).isTrue();
    }

    @Test
    void hasManagePermissionForOrganization_returnsFalseWhenNoTeamGrantsAccess() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org()));
        Team readOnlyTeam = new Team();
        readOnlyTeam.setRole("read");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(readOnlyTeam));
        when(rbacService.canManageWorkspace(readOnlyTeam)).thenReturn(false);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermissionForOrganization(token, orgId.toString())).isFalse();
    }

    @Test
    void hasManagePermissionForWorkspace_internalIssuerBypassesAllChecks() {
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "TerrakubeInternal"));

        assertThat(subject.hasManagePermissionForWorkspace(token, workspaceId.toString())).isTrue();
    }

    @Test
    void hasManagePermissionForWorkspace_returnsFalseWhenWorkspaceNotFound() {
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.empty());
        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermissionForWorkspace(token, workspaceId.toString())).isFalse();
    }

    @Test
    void hasManagePermissionForWorkspace_returnsTrueViaOrgWideAccess() {
        Workspace workspace = workspace();
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        Team writeTeam = new Team();
        writeTeam.setRole("write");
        when(teamRepository.findAllByOrganizationIdAndNameIn(eq(orgId), any())).thenReturn(List.of(writeTeam));
        when(rbacService.canManageWorkspace(writeTeam)).thenReturn(true);

        JwtAuthenticationToken token = authWithClaims(Map.of("iss", "Terrakube", "groups", List.of("team-a")));

        assertThat(subject.hasManagePermissionForWorkspace(token, workspaceId.toString())).isTrue();
    }
}
