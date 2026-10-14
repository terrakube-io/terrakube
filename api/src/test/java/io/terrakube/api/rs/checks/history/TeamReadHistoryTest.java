package io.terrakube.api.rs.checks.history;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacV2Service;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamReadHistoryTest {

    private final AuthenticatedUser authenticatedUser = mock(AuthenticatedUser.class);
    private final GroupService groupService = mock(GroupService.class);
    private final RequestScope scope = mock(RequestScope.class);
    private final TeamReadHistory check = new TeamReadHistory(authenticatedUser, groupService, new RbacV2Service());
    private Workspace workspace;
    private History history;

    @BeforeEach
    void setUp() {
        authenticate("https://dex.example/dex");
        when(groupService.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("STATE_TEAM"));
        workspace = new Workspace();
        workspace.setOrganization(new Organization());
        history = new History();
        history.setWorkspace(workspace);
    }

    @ParameterizedTest
    @CsvSource({
            "organization,admin,false,true", "organization,write,false,true",
            "organization,custom,true,true", "organization,custom,false,false",
            "organization,read,true,false", "organization,plan,true,false",
            "workspace,admin,false,true", "workspace,write,false,true",
            "workspace,custom,true,true", "workspace,custom,false,false",
            "workspace,read,true,false", "workspace,plan,true,false",
            "project,admin,false,true", "project,write,false,true",
            "project,custom,true,true", "project,custom,false,false",
            "project,read,true,false", "project,plan,true,false"
    })
    void followsManageStateRbacAtEveryGrantLevel(String level, String role, boolean manageState, boolean expected) {
        grant(level, role, manageState);
        assertEquals(expected, check.ok(history, scope, Optional.empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"organization", "workspace", "project"})
    void patUsersCanReadAndUnrelatedGroupsCannot(String level) {
        authenticate("Terrakube");
        when(groupService.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("STATE_TEAM"));
        grant(level, "custom", true);
        assertTrue(check.ok(history, scope, Optional.empty()));

        when(groupService.getEffectiveGroups(scope.getUser())).thenReturn(Set.of("OTHER_TEAM"));
        assertFalse(check.ok(history, scope, Optional.empty()));
    }

    @Test
    void legacyBooleanGrantsAndInstanceAdministratorsRemainSupported() {
        grant("organization", null, true);
        assertTrue(check.ok(history, scope, Optional.empty()));
        workspace.getOrganization().getTeam().getFirst().setManageState(false);
        assertFalse(check.ok(history, scope, Optional.empty()));

        when(authenticatedUser.isSuperUser(scope.getUser())).thenReturn(true);
        assertTrue(check.ok(history, scope, Optional.empty()));
    }

    @Test
    void internalGroupClaimsCannotBypassExecutorIsolation() {
        authenticate("TerrakubeInternal");
        when(authenticatedUser.isSuperUser(scope.getUser())).thenReturn(true);
        grant("organization", "admin", true);
        assertFalse(check.ok(history, scope, Optional.empty()));
    }

    @Test
    void aWorkspaceGrantDoesNotExposeHistoryFromAnotherWorkspace() {
        grant("workspace", "admin", true);
        assertTrue(check.ok(history, scope, Optional.empty()));
        Workspace other = new Workspace();
        other.setOrganization(workspace.getOrganization());
        history.setWorkspace(other);
        assertFalse(check.ok(history, scope, Optional.empty()));
    }

    @Test
    void missingIdentityOrOwningWorkspaceFailsClosed() {
        assertFalse(check.ok(null, scope, Optional.empty()));
        history.setWorkspace(null);
        assertFalse(check.ok(history, scope, Optional.empty()));
        history.setWorkspace(new Workspace());
        assertFalse(check.ok(history, scope, Optional.empty()));
        history.setWorkspace(workspace);
        when(scope.getUser()).thenReturn(null);
        assertFalse(check.ok(history, scope, Optional.empty()));
        when(scope.getUser()).thenReturn(new User(() -> "unauthenticated"));
        assertFalse(check.ok(history, scope, Optional.empty()));
    }

    @Test
    void emptyGroupsAndAbsentGrantsDoNotExposeStateHistory() {
        assertFalse(check.ok(history, scope, Optional.empty()));
        grant("organization", "admin", true);
        when(groupService.getEffectiveGroups(scope.getUser())).thenReturn(Set.of());
        assertFalse(check.ok(history, scope, Optional.empty()));
    }

    private void authenticate(String issuer) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256")
                .issuer(issuer).subject("user").claim("groups", List.of("STATE_TEAM")).build();
        when(scope.getUser()).thenReturn(new User(new JwtAuthenticationToken(jwt)));
    }

    private void grant(String level, String role, boolean manageState) {
        switch (level) {
            case "organization" -> {
                Team team = new Team();
                team.setName("STATE_TEAM");
                team.setRole(role);
                team.setManageState(manageState);
                workspace.getOrganization().setTeam(List.of(team));
            }
            case "workspace" -> {
                Access access = new Access();
                access.setName("STATE_TEAM");
                access.setRole(role);
                access.setManageState(manageState);
                workspace.setAccess(List.of(access));
            }
            case "project" -> {
                ProjectAccess access = new ProjectAccess();
                access.setName("STATE_TEAM");
                access.setRole(role);
                access.setManageState(manageState);
                Project project = new Project();
                project.setProjectAccess(List.of(access));
                workspace.setProject(project);
            }
            default -> throw new IllegalArgumentException(level);
        }
    }
}
