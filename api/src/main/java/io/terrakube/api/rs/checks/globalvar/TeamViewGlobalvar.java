package io.terrakube.api.rs.checks.globalvar;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewGlobalvar.RULE)
public class TeamViewGlobalvar extends OperationCheck<Globalvar> {

    public static final String RULE = "team view globalvar";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Override
    public boolean ok(Globalvar globalvar, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (globalvar == null || globalvar.getOrganization() == null) {
            return false;
        }
        log.debug("team view globalvar {}", globalvar.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = globalvar.getOrganization().getTeam();
        return membershipService.checkMembership(requestScope.getUser(), teamList);
    }
}

