package io.terrakube.api.plugin.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * Mirrors Workspace's own three-tier manage permission (org-wide team access,
 * OR a workspace-level Access grant, OR a project-level ProjectAccess grant)
 * for workspace-scoped notification operations, so anyone who can otherwise
 * manage a workspace can also manage/view its notifications - not just teams
 * with blanket org-wide "manage workspace" rights. Org-scoped configurations
 * only have the org-wide tier to check against (no specific workspace).
 */
@Service
public class NotificationConfigurationAccessService {

    private final TeamRepository teamRepository;
    private final NotificationConfigurationRepository notificationConfigurationRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final RbacService rbacService;

    public NotificationConfigurationAccessService(TeamRepository teamRepository,
            NotificationConfigurationRepository notificationConfigurationRepository,
            OrganizationRepository organizationRepository, WorkspaceRepository workspaceRepository,
            RbacService rbacService) {
        this.teamRepository = teamRepository;
        this.notificationConfigurationRepository = notificationConfigurationRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.rbacService = rbacService;
    }

    @Transactional
    public boolean hasManagePermission(Authentication authentication, String configurationId) {
        if (isInternalIssuer(authentication)) {
            return true;
        }
        NotificationConfiguration configuration = notificationConfigurationRepository
                .findById(UUID.fromString(configurationId)).orElse(null);
        if (configuration == null) {
            return false;
        }
        if (configuration.getWorkspace() != null) {
            return hasManagePermissionForWorkspace(authentication, configuration.getWorkspace());
        }
        return hasManagePermissionForOrganization(authentication, configuration.getOrganization());
    }

    @Transactional
    public boolean hasManagePermissionForOrganization(Authentication authentication, String organizationId) {
        if (isInternalIssuer(authentication)) {
            return true;
        }
        Organization organization = organizationRepository.findById(UUID.fromString(organizationId)).orElse(null);
        if (organization == null) {
            return false;
        }
        return hasManagePermissionForOrganization(authentication, organization);
    }

    @Transactional
    public boolean hasManagePermissionForWorkspace(Authentication authentication, String workspaceId) {
        if (isInternalIssuer(authentication)) {
            return true;
        }
        Workspace workspace = workspaceRepository.findById(UUID.fromString(workspaceId)).orElse(null);
        if (workspace == null) {
            return false;
        }
        return hasManagePermissionForWorkspace(authentication, workspace);
    }

    private boolean isInternalIssuer(Authentication authentication) {
        return ((JwtAuthenticationToken) authentication).getTokenAttributes().get("iss").equals("TerrakubeInternal");
    }

    @SuppressWarnings("unchecked")
    private List<String> groupNames(Authentication authentication) {
        Object groupNames = ((JwtAuthenticationToken) authentication).getTokenAttributes().get("groups");
        return groupNames == null ? List.of() : (List<String>) groupNames;
    }

    private boolean hasManagePermissionForOrganization(Authentication authentication, Organization organization) {
        List<String> groupNames = groupNames(authentication);
        if (groupNames.isEmpty()) {
            return false;
        }
        List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(organization.getId(), groupNames);
        for (Team team : teams) {
            if (rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasManagePermissionForWorkspace(Authentication authentication, Workspace workspace) {
        if (hasManagePermissionForOrganization(authentication, workspace.getOrganization())) {
            return true;
        }

        List<String> groupNames = groupNames(authentication);
        if (groupNames.isEmpty()) {
            return false;
        }

        List<Access> accessList = workspace.getAccess();
        if (accessList != null) {
            for (Access access : accessList) {
                if (groupNames.contains(access.getName()) && rbacService.canManageWorkspace(access)) {
                    return true;
                }
            }
        }

        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null) {
            for (ProjectAccess access : project.getProjectAccess()) {
                if (groupNames.contains(access.getName()) && rbacService.canManageWorkspace(access)) {
                    return true;
                }
            }
        }
        return false;
    }
}
