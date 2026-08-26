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
@SecurityCheck(TeamProjectAdminManagesProjectAccessField.RULE)
public class TeamProjectAdminManagesProjectAccessField extends OperationCheck<Project> {

    public static final String RULE = "team project admin manages project access field";

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Project project, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team project admin manages project access field {}", project.getId());
        List<ProjectAccess> accessList = project.getProjectAccess();
        if (accessList == null || accessList.isEmpty()) return false;
        return membershipService.checkProjectMembership(
                requestScope.getUser(), accessList, pa -> rbacService.canManageProject(pa));
    }
}
