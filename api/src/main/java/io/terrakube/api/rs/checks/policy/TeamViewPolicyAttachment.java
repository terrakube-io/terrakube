package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewPolicyAttachment.RULE)
public class TeamViewPolicyAttachment extends OperationCheck<PolicyAttachment> {

    public static final String RULE = "team view policy attachment";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Autowired
    GroupService groupService;

    @Override
    public boolean ok(PolicyAttachment attachment, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view policy attachment {}", attachment.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = attachment.getPolicySet().getOrganization().getTeam();
        if (teamList != null) {
            boolean isMember = membershipService.checkMembership(requestScope.getUser(), teamList);
            if (isMember) {
                return true;
            } else {
                return groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), attachment.getPolicySet().getOrganization());
            }
        }
        return false;
    }
}
