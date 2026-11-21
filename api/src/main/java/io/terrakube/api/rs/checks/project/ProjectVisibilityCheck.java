package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.List;

/**
 * SQL-level read check for an entity that is, or belongs to, a project. The rule itself lives in
 * {@link ProjectVisibility}; subclasses only say where the entity keeps its organization and project access.
 */
abstract class ProjectVisibilityCheck<T> extends FilterExpressionCheck<T> {

    private final GroupService groupService;
    private final RbacService rbacService;

    ProjectVisibilityCheck(GroupService groupService, RbacService rbacService) {
        this.groupService = groupService;
        this.rbacService = rbacService;
    }

    abstract Organization organizationOf(T entity);

    abstract List<ProjectAccess> projectAccessOf(T entity);

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        return ProjectVisibility.filter(entityClass, requestScope, groupService);
    }

    @Override
    public boolean applyPredicateToObject(T entity, FilterPredicate predicate, RequestScope requestScope) {
        // Single-resource checks cannot traverse relations hidden by Elide security.
        return ProjectVisibility.visible(organizationOf(entity), projectAccessOf(entity),
                groupService.getEffectiveGroups(requestScope.getUser()), rbacService);
    }
}
