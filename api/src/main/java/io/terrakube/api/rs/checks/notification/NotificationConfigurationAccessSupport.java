package io.terrakube.api.rs.checks.notification;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

// Shared team/access-tier lookups behind both TeamManageNotificationConfiguration (write access,
// and read access to workspace-scoped rows) and TeamReadNotificationConfiguration (read access to
// org-wide default rows for anyone who manages at least one workspace they apply to).
abstract class NotificationConfigurationAccessSupport extends OperationCheck<NotificationConfiguration> {

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    protected boolean hasOrgWideAccess(Organization organization, RequestScope requestScope) {
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = organization.getTeam();
        for (Team team : teamList) {
            boolean isMember = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), team.getName())
                    : groupService.isMember(requestScope.getUser(), team.getName());
            if (isMember && rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasWorkspaceLevelAccess(Workspace workspace, RequestScope requestScope) {
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Access> accessList = workspace.getAccess();
        if (accessList == null) {
            return false;
        }
        for (Access access : accessList) {
            boolean isMember = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), access.getName())
                    : groupService.isMember(requestScope.getUser(), access.getName());
            if (isMember && rbacService.canManageWorkspace(access)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasProjectLevelAccess(Workspace workspace, RequestScope requestScope) {
        Project project = workspace.getProject();
        if (project == null || project.getProjectAccess() == null || project.getProjectAccess().isEmpty()) {
            return false;
        }
        return membershipService.checkProjectMembership(
                requestScope.getUser(), project.getProjectAccess(), rbacService::canManageWorkspace);
    }

    protected boolean canManageAtLeastOneWorkspaceIn(Organization organization, RequestScope requestScope) {
        List<Workspace> workspaces = organization.getWorkspace();
        if (workspaces == null) {
            return false;
        }
        return workspaces.stream()
                .anyMatch(ws -> hasWorkspaceLevelAccess(ws, requestScope) || hasProjectLevelAccess(ws, requestScope));
    }
}
