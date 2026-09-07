package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewPolicySet.RULE)
public class TeamViewPolicySet extends OperationCheck<PolicySet> {

    public static final String RULE = "team view policy set";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Autowired
    GroupService groupService;

    @Override
    public boolean ok(PolicySet policySet, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view policy set {}", policySet.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = policySet.getOrganization().getTeam();
        if (teamList != null) {
            boolean isMember = membershipService.checkMembership(requestScope.getUser(), teamList);
            if (isMember) {
                return true;
            } else {
                return groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), policySet.getOrganization());
            }
        }
        return false;
    }
}
