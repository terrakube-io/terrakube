package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobVariablesAccessTest {
    private final AuthenticatedUser authenticatedUser = mock(AuthenticatedUser.class);
    private final GroupService groupService = mock(GroupService.class);
    private final RbacService rbacService = mock(RbacService.class);
    private final MembershipService membershipService = mock(MembershipService.class);
    private final JobVariablesAccess access =
            new JobVariablesAccess(authenticatedUser, groupService, rbacService, membershipService);
    private final User user = new User((Principal) () -> "someone");
    private final Team team = new Team();
    private Job job;

    @BeforeEach
    void setUp() {
        team.setName("TEAM");
        Organization organization = new Organization();
        organization.setTeam(List.of(team));
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        job = new Job();
        job.setWorkspace(workspace);
    }

    @Test
    void organizationTeamThatCanManageWorkspacesMaySetVariables() {
        when(groupService.isMember(user, "TEAM")).thenReturn(true);
        when(rbacService.canManageWorkspace(team)).thenReturn(true);
        assertTrue(access.canSet(user, job));
    }

    @Test
    void organizationWithoutATeamListIsRefusedInsteadOfFailing() {
        job.getWorkspace().getOrganization().setTeam(null);
        assertFalse(access.canSet(user, job));
    }

    @Test
    void memberOfATeamThatCanOnlyPlanMayNot() {
        when(groupService.isMember(user, "TEAM")).thenReturn(true);
        when(rbacService.canManageWorkspace(team)).thenReturn(false);
        assertFalse(access.canSet(user, job));
    }

    @Test
    void nonMemberMayNotEvenIfTheTeamCanManage() {
        when(rbacService.canManageWorkspace(team)).thenReturn(true);
        assertFalse(access.canSet(user, job));
    }

    @Test
    void instanceOwnerMaySetVariables() {
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);
        assertTrue(access.canSet(user, job));
    }

    @Test
    void jobWithoutWorkspaceIsRefused() {
        assertFalse(access.canSet(user, new Job()));
        when(membershipService.checkProjectMembership(any(), any(), any())).thenReturn(true);
        assertFalse(access.canSet(user, job));
    }
}
