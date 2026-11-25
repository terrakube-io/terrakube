package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.List;

/**
 * SQL-level read check for projects. It replaces the former in-memory {@code team view project} and
 * {@code team project access view project} checks on reads, because in-memory checks stop Elide from
 * applying explicit pagination and sorting.
 */
@SecurityCheck(ProjectReadFilter.RULE)
public class ProjectReadFilter extends ProjectVisibilityCheck<Project> {

    public static final String RULE = "project read filter";

    public ProjectReadFilter(GroupService groupService, RbacService rbacService) {
        super(groupService, rbacService);
    }

    @Override
    Organization organizationOf(Project project) {
        return project.getOrganization();
    }

    @Override
    List<ProjectAccess> projectAccessOf(Project project) {
        return project.getProjectAccess();
    }
}
