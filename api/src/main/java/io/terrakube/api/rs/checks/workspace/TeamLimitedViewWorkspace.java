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
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamLimitedViewWorkspace.RULE)
public class TeamLimitedViewWorkspace extends OperationCheck<Workspace> {

    public static final String RULE = "team limited view workspace";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited manage workspace {}", workspace.getId());
        boolean isTerrakubeService = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Access> teamsWithAccess = workspace.getAccess();
        for (Access team : teamsWithAccess) {
            if (isTerrakubeService){
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team) ){
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
