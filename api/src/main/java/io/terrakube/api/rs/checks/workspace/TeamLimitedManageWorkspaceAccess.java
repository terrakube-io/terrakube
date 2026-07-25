package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamLimitedManageWorkspaceAccess.RULE)
public class TeamLimitedManageWorkspaceAccess extends OperationCheck<Access> {

    public static final String RULE = "team limited manage workspace access";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Access access, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited manage workspace access {}", access.getId());
        Workspace workspace = access.getWorkspace();
        if (workspace == null) return false;
        List<Access> accessList = workspace.getAccess();
        if (accessList == null || accessList.isEmpty()) return false;
        for (Access existingAccess : accessList) {
            if (authenticatedUser.isServiceAccount(requestScope.getUser())) {
                if (groupService.isServiceMember(requestScope.getUser(), existingAccess.getName()) && rbacService.canManageWorkspace(existingAccess)) {
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), existingAccess.getName()) && rbacService.canManageWorkspace(existingAccess)) {
                    return true;
                }
            }
        }
        return false;
    }
}
