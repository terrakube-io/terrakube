package io.terrakube.api.rs.checks.provider;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.provider.implementation.Version;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewProviderVersion.RULE)
public class TeamViewProviderVersion extends OperationCheck<Version> {

    public static final String RULE = "team view provider version";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private GroupService groupService;

    @Override
    public boolean ok(Version version, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (version == null || version.getProvider() == null || version.getProvider().getOrganization() == null) {
            return false;
        }
        log.debug("team view provider version {}", version.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = version.getProvider().getOrganization().getTeam();
        if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
            return true;
        }
        return groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), version.getProvider().getOrganization())
                || groupService.isMemberWithProjectAccess(requestScope.getUser(), version.getProvider().getOrganization());
    }
}

