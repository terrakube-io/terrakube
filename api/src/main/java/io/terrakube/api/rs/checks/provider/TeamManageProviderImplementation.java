package io.terrakube.api.rs.checks.provider;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.provider.implementation.Implementation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageProviderImplementation.RULE)
public class TeamManageProviderImplementation extends OperationCheck<Implementation> {

    public static final String RULE = "team manage provider implementation";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RbacService rbacService;

    @Override
    public boolean ok(Implementation implementation, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (implementation == null || implementation.getVersion() == null || implementation.getVersion().getProvider() == null
                || implementation.getVersion().getProvider().getOrganization() == null) {
            return false;
        }
        log.debug("team manage provider implementation {}", implementation.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        return membershipService.checkTeamPermission(
                requestScope.getUser(),
                implementation.getVersion().getProvider().getOrganization().getTeam(),
                rbacService::canManageProvider
        );
    }
}

