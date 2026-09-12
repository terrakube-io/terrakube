package io.terrakube.api.plugin.security.policy;

import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service("policyWorkspaceAccessService")
public class PolicyWorkspaceAccessService {

    private final TeamRepository teamRepository;
    private final WorkspaceRepository workspaceRepository;
    private final RbacService rbacService;
    private final TeamTokenService teamTokenService;
    private final String instanceOwner;

    public PolicyWorkspaceAccessService(
            TeamRepository teamRepository,
            WorkspaceRepository workspaceRepository,
            RbacService rbacService,
            TeamTokenService teamTokenService,
            @Value("${io.terrakube.owner}") String instanceOwner) {
        this.teamRepository = teamRepository;
        this.workspaceRepository = workspaceRepository;
        this.rbacService = rbacService;
        this.teamTokenService = teamTokenService;
        this.instanceOwner = instanceOwner;
    }

    @Transactional(readOnly = true)
    public boolean hasPolicyEvaluationPermission(Authentication authentication, String organizationId, String workspaceId) {
        if (authentication == null) {
            return false;
        }

        if (authentication instanceof JwtAuthenticationToken principalJwt) {
            String issuer = (String) principalJwt.getTokenAttributes().get("iss");
            if ("TerrakubeInternal".equals(issuer)) {
                return true;
            }

            List<String> groups = teamTokenService.getCurrentGroups(principalJwt);
            if (groups == null || groups.isEmpty()) {
                return false;
            }

            if (instanceOwner != null && groups.contains(instanceOwner)) {
                return true;
            }

            // Check organization-level teams
            try {
                UUID orgUuid = UUID.fromString(organizationId);
                List<Team> teams = teamRepository.findAllByOrganizationIdAndNameIn(orgUuid, groups);
                for (Team team : teams) {
                    if (rbacService.canManageWorkspace(team) || rbacService.canPlanJob(team)) {
                        return true;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid organizationId UUID: {}", organizationId);
                return false;
            }

            // Check workspace-level and project-level access
            try {
                UUID wsUuid = UUID.fromString(workspaceId);
                Optional<Workspace> workspaceOptional = workspaceRepository.findById(wsUuid);
                if (workspaceOptional.isPresent()) {
                    Workspace workspace = workspaceOptional.get();
                    if (workspace.getAccess() != null) {
                        for (Access access : workspace.getAccess()) {
                            if (groups.contains(access.getName()) &&
                                    (rbacService.canManageWorkspace(access) || rbacService.canPlanJob(access))) {
                                return true;
                            }
                        }
                    }

                    if (workspace.getProject() != null && workspace.getProject().getProjectAccess() != null) {
                        for (ProjectAccess pa : workspace.getProject().getProjectAccess()) {
                            if (groups.contains(pa.getName()) &&
                                    (rbacService.canManageWorkspace(pa) || rbacService.canPlanJob(pa))) {
                                return true;
                            }
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid workspaceId UUID: {}", workspaceId);
                return false;
            }
        }

        return false;
    }
}
