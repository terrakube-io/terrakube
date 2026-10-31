package io.terrakube.api.rs.checks.notification;

import com.yahoo.elide.core.security.RequestScope;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class NotificationCheckSupport {

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private GroupService groupService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private MembershipService membershipService;

    public boolean canManageConfiguration(NotificationConfiguration configuration, RequestScope requestScope) {
        if (configuration == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        Workspace workspace = configuration.getWorkspace();
        Organization organization = workspace != null ? workspace.getOrganization() : configuration.getOrganization();

        if (hasOrgWideAccess(organization, requestScope)) {
            return true;
        }
        if (workspace == null) {
            return false;
        }
        return hasWorkspaceLevelAccess(workspace, requestScope) || hasProjectLevelAccess(workspace, requestScope);
    }

    public boolean canReadConfiguration(NotificationConfiguration configuration, RequestScope requestScope) {
        if (configuration == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        Workspace workspace = configuration.getWorkspace();
        Organization organization = workspace != null ? workspace.getOrganization() : configuration.getOrganization();

        if (hasOrgWideAccess(organization, requestScope)) {
            return true;
        }
        if (workspace != null) {
            return hasWorkspaceLevelAccess(workspace, requestScope) || hasProjectLevelAccess(workspace, requestScope);
        }
        return canManageAtLeastOneWorkspaceIn(organization, requestScope);
    }

    public boolean hasOrgWideAccess(Organization organization, RequestScope requestScope) {
        if (organization == null || organization.getTeam() == null) {
            return false;
        }
        List<Team> teamList = organization.getTeam();
        return membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace);
    }

    public boolean hasWorkspaceLevelAccess(Workspace workspace, RequestScope requestScope) {
        if (workspace == null || workspace.getAccess() == null) {
            return false;
        }
        List<Access> accessList = workspace.getAccess();
        return membershipService.checkAccessPermission(requestScope.getUser(), accessList, rbacService::canManageWorkspace);
    }

    public boolean hasProjectLevelAccess(Workspace workspace, RequestScope requestScope) {
        if (workspace == null) {
            return false;
        }
        Project project = workspace.getProject();
        if (project == null || project.getProjectAccess() == null || project.getProjectAccess().isEmpty()) {
            return false;
        }
        return membershipService.checkProjectMembership(
                requestScope.getUser(), project.getProjectAccess(), rbacService::canManageWorkspace);
    }

    public boolean canManageAtLeastOneWorkspaceIn(Organization organization, RequestScope requestScope) {
        if (organization == null) {
            return false;
        }
        List<Workspace> workspaces = organization.getWorkspace();
        if (workspaces == null) {
            return false;
        }
        return workspaces.stream()
                .anyMatch(ws -> hasWorkspaceLevelAccess(ws, requestScope) || hasProjectLevelAccess(ws, requestScope));
    }
}

