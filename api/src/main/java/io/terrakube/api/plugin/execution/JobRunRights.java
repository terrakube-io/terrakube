package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

/**
 * The rights that decide who may start or approve a run that changes infrastructure without anyone else in the loop.
 * Like the right to plan, each one can come from the organization team, the workspace access or the project access,
 * and any one of them is enough.
 *
 * <ul>
 *   <li>destroy: start a run that destroys with no approval in front of it, and approve a run that destroys. A team
 *       can plan and still never destroy.</li>
 *   <li>approve: the right to approve, here needed to start an apply with no approval in front of it, on a workspace
 *       that asks for it.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JobRunRights {
    private final AuthenticatedUser authenticatedUser;
    private final GroupService groupService;
    private final RbacService rbacService;
    private final MembershipService membershipService;

    public boolean canDestroy(User user, Workspace workspace) {
        return allowed(user, workspace, rbacService::canDestroyJob, rbacService::canDestroyJob,
                rbacService::canDestroyJob);
    }

    public boolean canApprove(User user, Workspace workspace) {
        return allowed(user, workspace, rbacService::canApproveJob, rbacService::canApproveJob,
                rbacService::canApproveJob);
    }

    private boolean allowed(User user, Workspace workspace, Function<Team, Boolean> team,
                            Function<Access, Boolean> access, Function<ProjectAccess, Boolean> projectAccess) {
        if (workspace == null) return false;
        if (authenticatedUser.isServiceAccountInternal(user) || authenticatedUser.isSuperUser(user)) return true;
        return hasOrganizationAccess(user, workspace, team) || hasWorkspaceAccess(user, workspace, access)
                || hasProjectAccess(user, workspace, projectAccess);
    }

    private boolean hasOrganizationAccess(User user, Workspace workspace, Function<Team, Boolean> allows) {
        if (workspace.getOrganization() == null || workspace.getOrganization().getTeam() == null) return false;
        boolean service = authenticatedUser.isServiceAccount(user);
        for (Team team : workspace.getOrganization().getTeam()) {
            if (isMember(user, service, team.getName()) && allows.apply(team)) return true;
        }
        return false;
    }

    private boolean hasWorkspaceAccess(User user, Workspace workspace, Function<Access, Boolean> allows) {
        List<Access> accessList = workspace.getAccess();
        if (accessList == null) return false;
        boolean service = authenticatedUser.isServiceAccount(user);
        for (Access access : accessList) {
            if (isMember(user, service, access.getName()) && allows.apply(access)) return true;
        }
        return false;
    }

    private boolean hasProjectAccess(User user, Workspace workspace, Function<ProjectAccess, Boolean> allows) {
        Project project = workspace.getProject();
        if (project == null || project.getProjectAccess() == null || project.getProjectAccess().isEmpty()) return false;
        return membershipService.checkProjectMembership(user, project.getProjectAccess(), allows);
    }

    private boolean isMember(User user, boolean service, String group) {
        return service ? groupService.isServiceMember(user, group) : groupService.isMember(user, group);
    }

    // The messages have no quotes: Elide HTML-escapes error messages.

    /** For a user who may plan and starts a destroy that nobody has to approve. */
    public static String destroyRefusal(Workspace workspace) {
        return "Your teams can plan in workspace " + workspace.getName() + " but cannot start a run that destroys "
                + "without an approval step. Use a template that asks for an approval first, or ask an administrator of "
                + "the workspace for the destroy permission.";
    }

    /** For a user who may plan and starts an apply that nobody has to approve, on a workspace that asks for approval. */
    public static String applyRefusal(Workspace workspace) {
        return "Workspace " + workspace.getName() + " requires the permission to approve runs to start an apply that "
                + "has no approval step. Use a template that asks for an approval first, or ask an administrator of the "
                + "workspace for the approve permission.";
    }

    /** For a user who may approve and is not allowed to destroy. */
    public static String approveDestroyRefusal(Workspace workspace) {
        return "Your teams cannot approve a run that destroys in workspace " + workspace.getName() + ". Ask an "
                + "administrator of the workspace for the destroy permission.";
    }
}
