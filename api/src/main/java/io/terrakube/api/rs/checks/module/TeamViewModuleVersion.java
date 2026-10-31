package io.terrakube.api.rs.checks.module;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewModuleVersion.RULE)
public class TeamViewModuleVersion extends OperationCheck<ModuleVersion> {

    public static final String RULE = "team view module version";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private GroupService groupService;

    @Override
    public boolean ok(ModuleVersion moduleVersion, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (moduleVersion == null || moduleVersion.getModule() == null || moduleVersion.getModule().getOrganization() == null) {
            return false;
        }
        log.debug("team view module version {}", moduleVersion.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = moduleVersion.getModule().getOrganization().getTeam();
        if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
            return true;
        }
        return groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), moduleVersion.getModule().getOrganization())
                || groupService.isMemberWithProjectAccess(requestScope.getUser(), moduleVersion.getModule().getOrganization());
    }
}

