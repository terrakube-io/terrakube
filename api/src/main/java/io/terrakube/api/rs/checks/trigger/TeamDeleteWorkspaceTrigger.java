package io.terrakube.api.rs.checks.trigger;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * Deleting a run trigger requires manage rights on the destination workspace only.
 *
 * Creating one also requires visibility of the source, but requiring that to remove it would
 * be a trap: if the source is later moved into a project the destination team cannot see,
 * they would be unable to detach a trigger that keeps firing runs on their own workspace.
 * Removing an unwanted dependency should never need permission from the other end.
 */
@Slf4j
@SecurityCheck(TeamDeleteWorkspaceTrigger.RULE)
public class TeamDeleteWorkspaceTrigger extends OperationCheck<WorkspaceRunTrigger> {

    public static final String RULE = "team delete workspace trigger";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    WorkspaceTriggerPermissions permissions;

    @Override
    public boolean ok(WorkspaceRunTrigger trigger, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        log.debug("team delete workspace trigger {}", trigger.getId());

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        if (trigger.getDestinationWorkspace() == null) {
            return false;
        }

        return permissions.canManage(trigger.getDestinationWorkspace(), requestScope);
    }
}
