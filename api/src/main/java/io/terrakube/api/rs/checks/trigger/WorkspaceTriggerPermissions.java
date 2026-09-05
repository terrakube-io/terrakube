package io.terrakube.api.rs.checks.trigger;

import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.project.access.ProjectAccess;
import com.yahoo.elide.core.security.RequestScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Workspace permission resolution shared by the run trigger checks.
 *
 * Terrakube grants workspace rights at three tiers - organization team, project access and
 * workspace-level access - and a trigger check has to honour all three, otherwise a team
 * that manages its workspace through a project is locked out of configuring triggers on it.
 *
 * The existing checks (TeamManageWorkspace and friends) cannot be reused directly: Elide
 * instantiates them, they are not Spring beans, so they cannot be injected here. This
 * component holds the equivalent logic in one place instead of duplicating it across the
 * two trigger checks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceTriggerPermissions {

    private final AuthenticatedUser authenticatedUser;
    private final GroupService groupService;
    private final RbacService rbacService;
    private final MembershipService membershipService;

    /** Manage rights on a workspace, across the three tiers. */
    public boolean canManage(Workspace workspace, RequestScope requestScope) {
        return canManageAtOrganization(workspace, requestScope)
                || canManageAtProject(workspace, requestScope)
                || canManageAtWorkspace(workspace, requestScope);
    }

    /**
     * View rights on a workspace. Mirrors TeamViewWorkspace: a workspace that belongs to a
     * project is only visible through that project's access, so plain organization
     * membership is not enough to target it as a trigger source.
     */
    public boolean canView(Workspace workspace, RequestScope requestScope) {
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (workspace.getOrganization() == null) {
            return false;
        }
        List<Team> teamList = workspace.getOrganization().getTeam();

        if (workspace.getProject() == null) {
            return membershipService.checkMembership(requestScope.getUser(), teamList);
        }
        // Inside a project, visibility follows the same rules as managing it.
        return canManage(workspace, requestScope);
    }

    private boolean canManageAtOrganization(Workspace workspace, RequestScope requestScope) {
        if (workspace.getOrganization() == null) {
            return false;
        }
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Team team : workspace.getOrganization().getTeam()) {
            boolean member = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), team.getName())
                    : groupService.isMember(requestScope.getUser(), team.getName());
            if (member && rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }

    private boolean canManageAtProject(Workspace workspace, RequestScope requestScope) {
        Project project = workspace.getProject();
        if (project == null) {
            return false;
        }
        List<ProjectAccess> accessList = project.getProjectAccess();
        if (accessList == null || accessList.isEmpty()) {
            return false;
        }
        return membershipService.checkProjectMembership(
                requestScope.getUser(), accessList, rbacService::canManageWorkspace);
    }

    private boolean canManageAtWorkspace(Workspace workspace, RequestScope requestScope) {
        List<Access> accessList = workspace.getAccess();
        if (accessList == null || accessList.isEmpty()) {
            return false;
        }
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Access access : accessList) {
            boolean member = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), access.getName())
                    : groupService.isMember(requestScope.getUser(), access.getName());
            if (member && rbacService.canManageWorkspace(access)) {
                return true;
            }
        }
        return false;
    }
}
