package io.terrakube.api.plugin.proxy;

import io.terrakube.api.plugin.security.rbac.RbacService;
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
public class ProxyAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final TeamRepository teamRepository;
    private final RbacService rbacService;
    private final String instanceOwner;

    public ProxyAccessService(
            WorkspaceRepository workspaceRepository,
            TeamRepository teamRepository,
            RbacService rbacService,
            @Value("${io.terrakube.owner:}") String instanceOwner) {
        this.workspaceRepository = workspaceRepository;
        this.teamRepository = teamRepository;
        this.rbacService = rbacService;
        this.instanceOwner = instanceOwner;
    }

    @Transactional(readOnly = true)
    public boolean hasProxyPermission(Authentication authentication, UUID workspaceId) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        List<String> groups = extractGroups(jwt);

        // Instance superuser has access across all workspaces
        if (instanceOwner != null && !instanceOwner.isBlank() && groups.contains(instanceOwner)) {
            return true;
        }

        if (workspaceId == null) {
            return false;
        }

        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return false;
        }

        // 1. Organization level check
        Organization organization = workspace.getOrganization();
        if (organization != null) {
            List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(organization.getId(), groups);
            for (Team team : teams) {
                if (rbacService.canManageWorkspace(team)) {
                    return true;
                }
            }
        }

        // 2. Workspace level Access check
        List<Access> accessList = workspace.getAccess();
        if (accessList != null) {
            for (Access access : accessList) {
                if (groups.contains(access.getName()) && rbacService.canManageWorkspace(access)) {
                    return true;
                }
            }
        }

        // 3. Project level ProjectAccess check
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
