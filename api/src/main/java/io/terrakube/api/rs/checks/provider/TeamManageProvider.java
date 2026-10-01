package io.terrakube.api.rs.checks.provider;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.provider.Provider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageProvider.RULE)
public class TeamManageProvider extends OperationCheck<Provider> {

    public static final String RULE = "team manage provider";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Provider provider, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage provider {}", provider.getId());
        return membershipService.checkTeamPermission(requestScope.getUser(), provider.getOrganization().getTeam(), rbacService::canManageProvider);
    }
}
