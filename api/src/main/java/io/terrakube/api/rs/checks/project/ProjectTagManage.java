package io.terrakube.api.rs.checks.project;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;

import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.tag.ProjectTag;
import io.terrakube.api.rs.team.Team;

/**
 * Who may change a project's default tags: the same people who may change the project itself, that is an
 * organization team that can manage workspaces, or a team with the admin role on the project.
 */
@SecurityCheck(ProjectTagManage.RULE)
public class ProjectTagManage extends OperationCheck<ProjectTag> {

    public static final String RULE = "project tag manage";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(ProjectTag tag, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        Project project = tag.getProject();
        if (project == null || project.getOrganization() == null) {
            return false;
        }
        for (Team team : project.getOrganization().getTeam()) {
            boolean member = authenticatedUser.isServiceAccount(requestScope.getUser())
                    ? groupService.isServiceMember(requestScope.getUser(), team.getName())
                    : groupService.isMember(requestScope.getUser(), team.getName());
            if (member && rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return membershipService.checkProjectMembership(requestScope.getUser(), project.getProjectAccess(),
                rbacService::canManageProject);
    }
}
