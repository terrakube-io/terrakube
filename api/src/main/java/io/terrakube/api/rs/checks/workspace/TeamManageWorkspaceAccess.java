package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageWorkspaceAccess.RULE)
public class TeamManageWorkspaceAccess extends OperationCheck<Access> {

    public static final String RULE = "team manage workspace access";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Access access, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage workspace access {}", access.getId());
        List<Team> teamList = access.getWorkspace().getOrganization().getTeam();
        for (Team team : teamList) {
            if (authenticatedUser.isServiceAccount(requestScope.getUser())) {
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team)) {
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team)) {
                    return true;
                }
            }
        }
        return false;
    }
}
