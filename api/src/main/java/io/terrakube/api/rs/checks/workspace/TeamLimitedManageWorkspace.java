package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamLimitedManageWorkspace.RULE)
public class TeamLimitedManageWorkspace extends OperationCheck<Workspace> {

    public static final String RULE = "team limited manage workspace";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited manage workspace {}", workspace.getId());
        boolean isService = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Access> teamsWithLimitedAccess = workspace.getAccess();
        if (!teamsWithLimitedAccess.isEmpty())
            for (Access team : teamsWithLimitedAccess) {
                if (isService) {
                    if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team)) {
                        return true;
                    }
                } else {
                    if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team))
                        return true;
                }
            }
        return false;
    }
}
