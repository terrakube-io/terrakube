package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.filter.expression.AndFilterExpression;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.expression.OrFilterExpression;
import com.yahoo.elide.core.filter.predicates.FalsePredicate;
import com.yahoo.elide.core.filter.predicates.InInsensitivePredicate;
import com.yahoo.elide.core.filter.predicates.InPredicate;
import com.yahoo.elide.core.filter.predicates.IsNullPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.List;
import java.util.Set;

/**
 * The single definition of who can see a project. {@code Project} and {@code ProjectSummary} both use it, so the
 * list a user pages through and the roll-up counts shown next to it can never disagree.
 *
 * <p>A project is visible to a member of an organization team that can manage workspaces, and to a member of any
 * team with a {@code ProjectAccess} row on it. Both paths also grant read access to every workspace in the project
 * (see {@code WorkspaceReadFilter}), so project level aggregates never reveal an unreadable workspace.
 *
 * <p>The entity must expose {@code organization.team} and {@code projectAccess} relationships.
 */
final class ProjectVisibility {

    private ProjectVisibility() {
    }

    static FilterExpression filter(Type<?> entityClass, RequestScope requestScope, GroupService groupService) {
        Object[] groups = groupService.getEffectiveGroups(requestScope.getUser()).toArray();
        if (groups.length == 0) {
            return new FalsePredicate(path(entityClass, requestScope, "id"));
        }

        FilterExpression organizationAccess = new AndFilterExpression(
                new InPredicate(path(entityClass, requestScope, "organization.team.name"), groups),
                canManageWorkspace(entityClass, requestScope));
        FilterExpression projectAccess =
                new InPredicate(path(entityClass, requestScope, "projectAccess.name"), groups);

        return new OrFilterExpression(organizationAccess, projectAccess);
    }

    /** In-memory twin of {@link #filter}, used for single-resource checks. */
    static boolean visible(Organization organization, List<ProjectAccess> projectAccess, Set<String> groups,
                           RbacService rbacService) {
        if (groups.isEmpty()) {
            return false;
        }
        boolean organizationAccess = organization != null
                && organization.getTeam() != null
                && organization.getTeam().stream()
                        .anyMatch(team -> groups.contains(team.getName()) && rbacService.canManageWorkspace(team));
        boolean projectLevelAccess = projectAccess != null
                && projectAccess.stream().anyMatch(access -> groups.contains(access.getName()));
        return organizationAccess || projectLevelAccess;
    }

    private static FilterExpression canManageWorkspace(Type<?> entityClass, RequestScope requestScope) {
        Path role = path(entityClass, requestScope, "organization.team.role");
        FilterExpression customRole = new OrFilterExpression(
                new InInsensitivePredicate(role, "", "custom"),
                new IsNullPredicate(role));
        return new OrFilterExpression(
                new InInsensitivePredicate(role, "admin", "write"),
                new AndFilterExpression(
                        customRole,
                        // TruePredicate renders as "(1 = 1)" and ignores its path, so compare the column explicitly.
                        new InPredicate(path(entityClass, requestScope, "organization.team.manageWorkspace"), true)));
    }

    private static Path path(Type<?> entityClass, RequestScope requestScope, String field) {
        return new Path(entityClass, ((com.yahoo.elide.core.RequestScope) requestScope).getDictionary(), field);
    }
}
