package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamProjectLimitedManageWorkspace.RULE)
public class TeamProjectLimitedManageWorkspace extends OperationCheck<Workspace> {

    public static final String RULE = "team project limited manage workspace";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team project limited manage workspace {}", workspace.getId());
        Project project = workspace.getProject();
        if (project == null) return false;
        List<ProjectAccess> accessList = project.getProjectAccess();
        if (accessList == null || accessList.isEmpty()) return false;
        return membershipService.checkProjectMembership(
                requestScope.getUser(), accessList, pa -> rbacService.canManageWorkspace(pa));
    }
}
