package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * Mirrors Workspace's own three-tier update permission ("team manage workspace OR team project limited manage
 * workspace OR team limited manage workspace") for its tag bindings. Without it Elide only guards adding a
 * binding through the workspace relationship, leaving any organization member free to change or delete the
 * value of an existing one.
 */
@Slf4j
@SecurityCheck(TeamManageWorkspaceTag.RULE)
public class TeamManageWorkspaceTag extends OperationCheck<WorkspaceTag> {

    public static final String RULE = "team manage workspace tag";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(WorkspaceTag workspaceTag, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage workspace tag {}", workspaceTag.getId());
        Workspace workspace = workspaceTag.getWorkspace();
        if (workspace == null) {
            return false;
        }
        return hasOrgWideAccess(workspace, requestScope)
                || hasWorkspaceLevelAccess(workspace, requestScope)
                || hasProjectLevelAccess(workspace, requestScope);
    }

    private boolean hasOrgWideAccess(Workspace workspace, RequestScope requestScope) {
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = workspace.getOrganization().getTeam();
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

    private boolean hasWorkspaceLevelAccess(Workspace workspace, RequestScope requestScope) {
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

    private boolean hasProjectLevelAccess(Workspace workspace, RequestScope requestScope) {
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
}
