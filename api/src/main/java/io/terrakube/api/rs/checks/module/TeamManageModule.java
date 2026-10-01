package io.terrakube.api.rs.checks.module;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.module.Module;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageModule.RULE)
public class TeamManageModule extends OperationCheck<Module> {

    public static final String RULE = "team manage module";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Module module, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage module {}", module.getId());
        return membershipService.checkTeamPermission(requestScope.getUser(), module.getOrganization().getTeam(), rbacService::canManageModule);
    }
}
