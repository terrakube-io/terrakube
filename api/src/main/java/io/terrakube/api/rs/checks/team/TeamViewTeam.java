package io.terrakube.api.rs.checks.team;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewTeam.RULE)
public class TeamViewTeam extends OperationCheck<Team> {

    public static final String RULE = "team view team";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private GroupService groupService;

    @Override
    public boolean ok(Team team, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (team == null || team.getOrganization() == null) {
            return false;
        }
        log.debug("team view team {}", team.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = team.getOrganization().getTeam();
        if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
            return true;
        }
        if (groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), team.getOrganization())) {
            return true;
        }
        return groupService.isMemberWithProjectAccess(requestScope.getUser(), team.getOrganization());
    }
}

