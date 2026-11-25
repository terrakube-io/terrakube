package io.terrakube.api.rs.checks.project;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;

import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.project.tag.ProjectTag;

/** A project's default tags can be read by whoever can see the project (the same rule as project reads). */
@SecurityCheck(ProjectTagVisible.RULE)
public class ProjectTagVisible extends OperationCheck<ProjectTag> {

    public static final String RULE = "project tag visible";

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(ProjectTag tag, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (tag.getProject() == null) {
            return false;
        }
        return ProjectVisibility.visible(tag.getProject().getOrganization(), tag.getProject().getProjectAccess(),
                groupService.getEffectiveGroups(requestScope.getUser()), rbacService);
    }
}
