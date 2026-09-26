package io.terrakube.api.rs.checks.module;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.module.ModuleVersion;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageModuleVersion.RULE)
public class TeamManageModuleVersion extends OperationCheck<ModuleVersion> {

    public static final String RULE = "team manage module version";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(ModuleVersion moduleVersion, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage module version {}", moduleVersion.getId());
        return membershipService.checkTeamPermission(requestScope.getUser(), moduleVersion.getModule().getOrganization().getTeam(), rbacService::canManageModule);
    }
}
