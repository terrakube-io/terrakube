package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Setting job variables overrides workspace variables for a run, so it needs the same right as editing the
 * workspace variables: manage workspace through the organization team, the workspace access or the project
 * access (the three rules of the workspace update permission), or the instance owner.
 */
@Service
@RequiredArgsConstructor
public class JobVariablesAccess {
    private final AuthenticatedUser authenticatedUser;
    private final GroupService groupService;
    private final RbacService rbacService;
    private final MembershipService membershipService;

    public boolean canSet(User user, Job job) {
        Workspace workspace = job.getWorkspace();
        if (workspace == null) return false;
        if (authenticatedUser.isSuperUser(user)) return true;
        boolean service = authenticatedUser.isServiceAccount(user);
        List<Team> teams = workspace.getOrganization() != null ? workspace.getOrganization().getTeam() : List.of();
        for (Team team : teams) {
            if (isMember(user, service, team.getName()) && rbacService.canManageWorkspace(team)) return true;
        }
        List<Access> access = workspace.getAccess();
        if (access != null) {
            for (Access team : access) {
                if (isMember(user, service, team.getName()) && rbacService.canManageWorkspace(team)) return true;
            }
        }
        Project project = workspace.getProject();
        if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
            return membershipService.checkProjectMembership(user, project.getProjectAccess(),
                    pa -> rbacService.canManageWorkspace(pa));
        }
        return false;
    }

    private boolean isMember(User user, boolean service, String group) {
        return service ? groupService.isServiceMember(user, group) : groupService.isMember(user, group);
    }
}
