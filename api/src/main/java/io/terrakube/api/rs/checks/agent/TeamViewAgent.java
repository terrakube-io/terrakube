package io.terrakube.api.rs.checks.agent;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.agent.Agent;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewAgent.RULE)
public class TeamViewAgent extends OperationCheck<Agent> {

    public static final String RULE = "team view agent";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Override
    public boolean ok(Agent agent, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (agent == null || agent.getOrganization() == null) {
            return false;
        }
        log.debug("team view agent {}", agent.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = agent.getOrganization().getTeam();
        return membershipService.checkMembership(requestScope.getUser(), teamList);
    }
}

