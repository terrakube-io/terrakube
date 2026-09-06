package io.terrakube.api;

import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.filter.predicates.FalsePredicate;
import com.yahoo.elide.core.type.ClassType;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.workspace.WorkspaceReadFilter;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceReadFilterTest {
    @Test
    void deniesEmptyGroupsAndHandlesUninitializedRelationships() {
        GroupService groups = mock(GroupService.class);
        RequestScope scope = mock(RequestScope.class);
        WorkspaceReadFilter filter = new WorkspaceReadFilter(groups, mock(RbacService.class));
        EntityDictionary dictionary = mock(EntityDictionary.class);
        when(scope.getDictionary()).thenReturn(dictionary);
        when(dictionary.getIdFieldName(ClassType.of(Workspace.class))).thenReturn("id");
        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of());

        assertInstanceOf(FalsePredicate.class, filter.getFilterExpression(ClassType.of(Workspace.class), scope));
        Workspace workspace = new Workspace();
        assertFalse(filter.applyPredicateToObject(workspace, null, scope));

        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("TEAM"));
        assertFalse(filter.applyPredicateToObject(workspace, null, scope));
        workspace.setOrganization(new Organization());
        workspace.setProject(new Project());
        workspace.getProject().setProjectAccess(null);
        assertFalse(filter.applyPredicateToObject(workspace, null, scope));

        ProjectAccess access = new ProjectAccess();
        access.setName("TEAM");
        workspace.getProject().setProjectAccess(List.of(access));
        assertTrue(filter.applyPredicateToObject(workspace, null, scope));
        access.setName("OTHER");
        assertFalse(filter.applyPredicateToObject(workspace, null, scope));
    }
}
