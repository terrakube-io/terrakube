package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamProjectLimitedManageProjectAccess.RULE)
public class TeamProjectLimitedManageProjectAccess extends OperationCheck<ProjectAccess> {

    public static final String RULE = "team project limited manage project access";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(ProjectAccess projectAccess, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team project limited manage project access {}", projectAccess.getId());
        Project project = projectAccess.getProject();
        if (project == null) return false;
        List<ProjectAccess> accessList = project.getProjectAccess();
        if (accessList == null || accessList.isEmpty()) return false;
        return membershipService.checkProjectMembership(
                requestScope.getUser(), accessList, pa -> rbacService.canManageProject(pa));
    }
}
