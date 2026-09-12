package io.terrakube.api.rs.checks.vcs;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewVcs.RULE)
public class TeamViewVcs extends OperationCheck<Vcs> {
    public static final String RULE = "team view vcs";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Autowired
    GroupService groupService;

    @Override
    public boolean ok(Vcs vcs, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view vcs {}", vcs.getId());
        List<Team> teamList = vcs.getOrganization().getTeam();
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        } else {
            boolean isMember = membershipService.checkMembership(requestScope.getUser(), teamList);

            if (isMember)
                return true;
            else
                return groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), vcs.getOrganization())
                        || groupService.isMemberWithProjectAccess(requestScope.getUser(), vcs.getOrganization());
        }
    }
}
