package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.filter.expression.AndFilterExpression;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.expression.OrFilterExpression;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.filter.predicates.InInsensitivePredicate;
import com.yahoo.elide.core.filter.predicates.InPredicate;
import com.yahoo.elide.core.filter.predicates.IsNullPredicate;
import com.yahoo.elide.core.filter.predicates.TruePredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;

import java.util.Set;

@SecurityCheck(WorkspaceReadFilter.RULE)
@AllArgsConstructor
public class WorkspaceReadFilter extends FilterExpressionCheck<Workspace> {

    public static final String RULE = "workspace read filter";

    private final GroupService groupService;
    private final RbacService rbacService;

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        Object[] groups = groupService.getEffectiveGroups(requestScope.getUser()).toArray();

        FilterExpression organizationAccess = new AndFilterExpression(
                new InPredicate(path(entityClass, requestScope, "organization.team.name"), groups),
                new OrFilterExpression(
                        new IsNullPredicate(path(entityClass, requestScope, "project.id")),
                        canManageWorkspace(entityClass, requestScope, "organization.team")));
        FilterExpression projectAccess =
                new InPredicate(path(entityClass, requestScope, "project.projectAccess.name"), groups);
        FilterExpression workspaceAccess = new AndFilterExpression(
                new InPredicate(path(entityClass, requestScope, "access.name"), groups),
                canManageWorkspace(entityClass, requestScope, "access"));

        return new OrFilterExpression(organizationAccess, new OrFilterExpression(projectAccess, workspaceAccess));
    }

    @Override
    public boolean applyPredicateToObject(
            Workspace workspace, FilterPredicate predicate, RequestScope requestScope) {
        // Single-resource checks cannot traverse relations hidden by Elide security.
        Set<String> groups = groupService.getEffectiveGroups(requestScope.getUser());
        boolean organizationAccess = workspace.getOrganization().getTeam().stream().anyMatch(team ->
                groups.contains(team.getName())
                        && (workspace.getProject() == null || rbacService.canManageWorkspace(team)));
        boolean projectAccess = workspace.getProject() != null
                && workspace.getProject().getProjectAccess().stream()
                        .anyMatch(access -> groups.contains(access.getName()));
        boolean workspaceAccess = workspace.getAccess().stream()
                .anyMatch(access -> groups.contains(access.getName()) && rbacService.canManageWorkspace(access));
        return organizationAccess || projectAccess || workspaceAccess;
    }

    private FilterExpression canManageWorkspace(Type<?> entityClass, RequestScope requestScope, String relation) {
        Path role = path(entityClass, requestScope, relation + ".role");
        FilterExpression customRole = new OrFilterExpression(
                new InInsensitivePredicate(role, "", "custom"),
                new IsNullPredicate(role));
        return new OrFilterExpression(
                new InInsensitivePredicate(role, "admin", "write"),
                new AndFilterExpression(
                        customRole,
                        new TruePredicate(path(entityClass, requestScope, relation + ".manageWorkspace"))));
    }

    private Path path(Type<?> entityClass, RequestScope requestScope, String field) {
        return new Path(entityClass, coreScope(requestScope).getDictionary(), field);
    }
}
