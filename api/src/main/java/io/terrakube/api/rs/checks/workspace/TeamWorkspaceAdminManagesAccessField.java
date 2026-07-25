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
@SecurityCheck(TeamWorkspaceAdminManagesAccessField.RULE)
public class TeamWorkspaceAdminManagesAccessField extends OperationCheck<Workspace> {

    public static final String RULE = "team workspace admin manages access field";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team workspace admin manages access field {}", workspace.getId());
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
