package io.terrakube.api.rs.checks.trigger;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * Creating, changing or deleting a run trigger requires manage rights on the
 * <strong>destination</strong> workspace: that is the one whose runs will start firing, so
 * it is the side that carries the consequence.
 *
 * The check is intentionally evaluated against both ends. Requiring manage on the
 * destination alone would let anyone with rights on a single workspace attach it to any
 * other workspace in the organization, including ones they cannot otherwise reach. Since a
 * trigger reveals when the source applies, this keeps the edge from becoming a side channel.
 *
 * Permissions are enforced when the edge is configured, not on every dispatch — re-checking
 * at trigger time would make an unrelated permission change silently break a pipeline.
 */
@Slf4j
@SecurityCheck(TeamManageWorkspaceTrigger.RULE)
public class TeamManageWorkspaceTrigger extends OperationCheck<WorkspaceRunTrigger> {

    public static final String RULE = "team manage workspace trigger";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(WorkspaceRunTrigger trigger, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        log.debug("team manage workspace trigger {}", trigger.getId());

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        Workspace destination = trigger.getDestinationWorkspace();
        Workspace source = trigger.getSourceWorkspace();
        if (destination == null || source == null) {
            return false;
        }

        // An edge may only exist inside a single organization; a mismatch here means the
        // payload is malformed, and letting it through would cross a tenant boundary.
        if (destination.getOrganization() == null || source.getOrganization() == null
                || !destination.getOrganization().getId().equals(source.getOrganization().getId())) {
            log.warn("Rejecting run trigger {}: source and destination belong to different organizations",
                    trigger.getId());
            return false;
        }

        return canManage(destination, requestScope) && canView(source, requestScope);
    }

    private boolean canManage(Workspace workspace, RequestScope requestScope) {
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = workspace.getOrganization().getTeam();
        for (Team team : teamList) {
            boolean member = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), team.getName())
                    : groupService.isMember(requestScope.getUser(), team.getName());
            if (member && rbacService.canManageWorkspace(team)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Being able to see the source is enough to depend on it; managing it is not required,
     * since the trigger does not change the source in any way.
     */
    private boolean canView(Workspace workspace, RequestScope requestScope) {
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = workspace.getOrganization().getTeam();
        for (Team team : teamList) {
            boolean member = isServiceAccount
                    ? groupService.isServiceMember(requestScope.getUser(), team.getName())
                    : groupService.isMember(requestScope.getUser(), team.getName());
            if (member) {
                return true;
            }
        }
        return false;
    }
}
