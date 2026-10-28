package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class WorkspaceCheckSupport {

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private GroupService groupService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private MembershipService membershipService;

    public boolean canViewWorkspace(Workspace workspace, RequestScope requestScope) {
        if (workspace == null || workspace.getOrganization() == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        // 1. Organization tier
        List<Team> teamList = workspace.getOrganization().getTeam();
        if (teamList != null) {
            if (workspace.getProject() == null) {
                if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
                    return true;
                }
            } else {
                if (membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace)) {
                    return true;
                }
            }
        }

        // 2. Workspace tier (direct Access bindings)
        List<Access> accessList = workspace.getAccess();
        if (accessList != null && membershipService.checkLimitedMembership(requestScope.getUser(), accessList)) {
            return true;
        }

        // 3. Project tier
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
            return membershipService.checkProjectMembership(
                    requestScope.getUser(), project.getProjectAccess(), pa -> true);
        }

        return false;
    }

    public boolean canManageWorkspace(Workspace workspace, RequestScope requestScope) {
        if (workspace == null || workspace.getOrganization() == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        // 1. Organization tier
        List<Team> teamList = workspace.getOrganization().getTeam();
        if (teamList != null && membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace)) {
            return true;
        }

        // 2. Workspace tier (direct Access bindings)
        List<Access> accessList = workspace.getAccess();
        if (accessList != null && membershipService.checkAccessPermission(requestScope.getUser(), accessList, rbacService::canManageWorkspace)) {
            return true;
        }

        // 3. Project tier
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
            return membershipService.checkProjectMembership(
                    requestScope.getUser(), project.getProjectAccess(), rbacService::canManageWorkspace);
        }

        return false;
    }
}

