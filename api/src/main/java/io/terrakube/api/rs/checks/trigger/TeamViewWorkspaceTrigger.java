package io.terrakube.api.rs.checks.trigger;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * Reading a run trigger is open to any member of the organization that owns it, so the
 * dependency graph is discoverable even by people who cannot change it.
 *
 * The edge itself is not sensitive: it says "workspace A feeds workspace B", not what
 * flows between them.
 */
@Slf4j
@SecurityCheck(TeamViewWorkspaceTrigger.RULE)
public class TeamViewWorkspaceTrigger extends OperationCheck<WorkspaceRunTrigger> {

    public static final String RULE = "team view workspace trigger";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(WorkspaceRunTrigger trigger, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        log.debug("team view workspace trigger {}", trigger.getId());

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        if (trigger.getOrganization() == null) {
            return false;
        }

        return membershipService.checkMembership(requestScope.getUser(), trigger.getOrganization().getTeam());
    }
}
