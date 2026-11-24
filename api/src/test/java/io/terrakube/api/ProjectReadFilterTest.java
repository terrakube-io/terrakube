package io.terrakube.api;

import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.filter.predicates.FalsePredicate;
import com.yahoo.elide.core.type.ClassType;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.project.ProjectReadFilter;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectReadFilterTest {

    private GroupService groups;
    private RbacService rbac;
    private RequestScope scope;
    private ProjectReadFilter filter;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        rbac = mock(RbacService.class);
        scope = mock(RequestScope.class);
        filter = new ProjectReadFilter(groups, rbac);
    }

    @Test
    void emptyGroupsSeeNothing() {
        EntityDictionary dictionary = mock(EntityDictionary.class);
        when(scope.getDictionary()).thenReturn(dictionary);
        when(dictionary.getIdFieldName(ClassType.of(Project.class))).thenReturn("id");
        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of());

        assertInstanceOf(FalsePredicate.class, filter.getFilterExpression(ClassType.of(Project.class), scope));
        assertFalse(filter.applyPredicateToObject(new Project(), null, scope));
    }

    @Test
    void organizationTeamThatManagesWorkspacesSeesTheProject() {
        Team manager = team("MANAGERS");
        Team reader = team("READERS");
        when(rbac.canManageWorkspace(manager)).thenReturn(true);
        when(rbac.canManageWorkspace(reader)).thenReturn(false);
        Project project = projectWithTeams(manager, reader);

        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("MANAGERS"));
        assertTrue(filter.applyPredicateToObject(project, null, scope));

        // Membership alone is not enough: the team must be able to manage workspaces.
        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("READERS"));
        assertFalse(filter.applyPredicateToObject(project, null, scope));
    }

    @Test
    void projectAccessTeamSeesOnlyItsOwnProject() {
        Project project = projectWithTeams();
        project.setProjectAccess(List.of(access("PROJECT_TEAM")));

        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("PROJECT_TEAM"));
        assertTrue(filter.applyPredicateToObject(project, null, scope));

        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("OTHER"));
        assertFalse(filter.applyPredicateToObject(project, null, scope));
    }

    @Test
    void uninitializedRelationshipsAreDenied() {
        when(groups.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("TEAM"));
        Project project = new Project();
        assertFalse(filter.applyPredicateToObject(project, null, scope));

        project.setOrganization(new Organization());
        project.setProjectAccess(null);
        assertFalse(filter.applyPredicateToObject(project, null, scope));
    }

    private static Project projectWithTeams(Team... teams) {
        Organization organization = new Organization();
        organization.setTeam(List.of(teams));
        Project project = new Project();
        project.setOrganization(organization);
        return project;
    }

    private static Team team(String name) {
        Team team = new Team();
        team.setName(name);
        return team;
    }

    private static ProjectAccess access(String name) {
        ProjectAccess access = new ProjectAccess();
        access.setName(name);
        return access;
    }
}
