package io.terrakube.api.rs.checks.provider;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.provider.implementation.Version;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageProviderVersion.RULE)
public class TeamManageProviderVersion extends OperationCheck<Version> {

    public static final String RULE = "team manage provider version";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Version version, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage provider version {}", version.getId());
        return membershipService.checkTeamPermission(requestScope.getUser(), version.getProvider().getOrganization().getTeam(), rbacService::canManageProvider);
    }
}
