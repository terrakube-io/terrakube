package io.terrakube.api.rs.checks.project;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.filter.predicates.FalsePredicate;
import com.yahoo.elide.core.filter.predicates.FilterPredicate;
import com.yahoo.elide.core.filter.predicates.InPredicate;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.FilterExpressionCheck;
import com.yahoo.elide.core.type.Type;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.Set;

/**
 * A caller may read the project access rows of the teams they are in, and no others. Project access in general is
 * for people who manage it, but "My projects" filters the project list on the caller's own team names, and Elide
 * checks read access along the path of a filter, row by row. Without this the filter dropped every project of
 * someone whose only grant is read, plan or write, and the chip count disagreed with the empty list.
 */
@SecurityCheck(ProjectAccessOwnTeam.RULE)
public class ProjectAccessOwnTeam extends FilterExpressionCheck<ProjectAccess> {

    public static final String RULE = "project access own team";

    private final GroupService groupService;

    public ProjectAccessOwnTeam(GroupService groupService) {
        this.groupService = groupService;
    }

    @Override
    public FilterExpression getFilterExpression(Type<?> entityClass, RequestScope requestScope) {
        Object[] groups = groupService.getEffectiveGroups(requestScope.getUser()).toArray();
        Path id = path(entityClass, requestScope, "id");
        if (groups.length == 0) {
            return new FalsePredicate(id);
        }
        return new InPredicate(path(entityClass, requestScope, "name"), groups);
    }

    @Override
    public boolean applyPredicateToObject(ProjectAccess access, FilterPredicate predicate, RequestScope requestScope) {
        Set<String> groups = groupService.getEffectiveGroups(requestScope.getUser());
        return access.getName() != null && groups.contains(access.getName());
    }

    private static Path path(Type<?> entityClass, RequestScope requestScope, String field) {
        return new Path(entityClass, ((com.yahoo.elide.core.RequestScope) requestScope).getDictionary(), field);
    }
}
