package io.terrakube.api.rs.checks.cascade;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import io.terrakube.api.rs.checks.membership.MembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/** Same visibility as {@link TeamViewRunCascade} - via the node's cascade this attempt belongs to. */
@Slf4j
@SecurityCheck(TeamViewRunCascadeNodeAttempt.RULE)
public class TeamViewRunCascadeNodeAttempt extends OperationCheck<RunCascadeNodeAttempt> {

    public static final String RULE = "team view run cascade node attempt";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(RunCascadeNodeAttempt attempt, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        Organization organization = attempt.getNode() != null && attempt.getNode().getCascade() != null
                ? attempt.getNode().getCascade().getOrganization()
                : null;
        if (organization == null) {
            return false;
        }

        return membershipService.checkMembership(requestScope.getUser(), organization.getTeam());
    }
}
