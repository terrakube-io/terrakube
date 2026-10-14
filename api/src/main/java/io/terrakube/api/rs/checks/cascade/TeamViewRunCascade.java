package io.terrakube.api.rs.checks.cascade;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.checks.membership.MembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * Reading a cascade is open to any member of the organization that owns it, the same
 * discoverability reasoning as {@code TeamViewWorkspaceTrigger} for the edges that create one.
 */
@Slf4j
@SecurityCheck(TeamViewRunCascade.RULE)
public class TeamViewRunCascade extends OperationCheck<RunCascade> {

    public static final String RULE = "team view run cascade";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(RunCascade cascade, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        if (cascade.getOrganization() == null) {
            return false;
        }

        return membershipService.checkMembership(requestScope.getUser(), cascade.getOrganization().getTeam());
    }
}
