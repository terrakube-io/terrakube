package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.ProjectSummary;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.List;

/** Read check for the project roll-up: exactly the projects the caller can read. */
@SecurityCheck(ProjectSummaryReadFilter.RULE)
public class ProjectSummaryReadFilter extends ProjectVisibilityCheck<ProjectSummary> {

    public static final String RULE = "project summary read filter";

    public ProjectSummaryReadFilter(GroupService groupService, RbacService rbacService) {
        super(groupService, rbacService);
    }

    @Override
    Organization organizationOf(ProjectSummary summary) {
        return summary.getOrganization();
    }

    @Override
    List<ProjectAccess> projectAccessOf(ProjectSummary summary) {
        return summary.getProjectAccess();
    }
}
