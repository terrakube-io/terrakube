package io.terrakube.api.plugin.migrate;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MigrateAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final RbacService rbacService;
    private final String instanceOwner;

    public MigrateAccessService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            TeamRepository teamRepository,
            RbacService rbacService,
            @Value("${io.terrakube.owner:}") String instanceOwner) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.teamRepository = teamRepository;
        this.rbacService = rbacService;
        this.instanceOwner = instanceOwner;
    }

    @Transactional(readOnly = true)
    public boolean hasMigrationPermission(Authentication authentication, String workspaceId, String organizationId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        List<String> groups = extractGroups(jwt);

        // 1. Platform Superuser / Instance Admin can migrate any workspace
        if (instanceOwner != null && !instanceOwner.isBlank() && groups.contains(instanceOwner)) {
            return true;
        }

        UUID wsUuid;
        UUID targetOrgUuid;
        try {
            wsUuid = UUID.fromString(workspaceId);
            targetOrgUuid = UUID.fromString(organizationId);
        } catch (Exception e) {
            return false;
        }

        // 2. Validate caller has manage permissions on SOURCE workspace
        Workspace workspace = workspaceRepository.findById(wsUuid).orElse(null);
        if (workspace == null) {
            return false;
        }

        if (!hasSourceWorkspaceManagePermission(workspace, groups)) {
            return false;
        }

        // 3. Validate caller has manage permissions on TARGET organization
        Organization targetOrg = organizationRepository.findById(targetOrgUuid).orElse(null);
        if (targetOrg == null) {
            return false;
        }

        return hasTargetOrganizationManagePermission(targetOrg, groups);
    }

    private boolean hasSourceWorkspaceManagePermission(Workspace workspace, List<String> groups) {
        // Organization tier
        Organization sourceOrg = workspace.getOrganization();
        if (sourceOrg != null) {
            List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(sourceOrg.getId(), groups);
            for (Team team : teams) {
                if (rbacService.canManageWorkspace(team)) {
                    return true;
                }
            }
        }

        // Workspace tier
        List<Access> accessList = workspace.getAccess();
        if (accessList != null) {
            for (Access access : accessList) {
                if (groups.contains(access.getName()) && rbacService.canManageWorkspace(access)) {
                    return true;
                }
            }
        }

        // Project tier
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null) {
            for (ProjectAccess access : project.getProjectAccess()) {
                if (groups.contains(access.getName()) && rbacService.canManageWorkspace(access)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasTargetOrganizationManagePermission(Organization targetOrg, List<String> groups) {
        List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(targetOrg.getId(), groups);
        for (Team team : teams) {
            if (rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractGroups(JwtAuthenticationToken jwt) {
        Map<String, Object> tokenAttributes = jwt.getTokenAttributes();
        List<String> groups = new ArrayList<>();
        Object tokenGroups = tokenAttributes.get("groups");

        if (tokenGroups instanceof Object[] values) {
            tokenGroups = Arrays.asList(values);
        }
        if (tokenGroups instanceof String group && !group.isBlank()) {
            groups.add(group);
        } else if (tokenGroups instanceof Collection<?> values) {
            values.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(g -> !g.isBlank())
                    .forEach(groups::add);
        }
        return groups;
    }
}
