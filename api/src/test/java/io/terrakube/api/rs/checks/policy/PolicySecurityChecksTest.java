package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyOverride;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.team.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class PolicySecurityChecksTest {

    private AuthenticatedUser authenticatedUser;
    private GroupService groupService;
    private RbacService rbacService;
    private RequestScope requestScope;
    private User user;

    @BeforeEach
    void setUp() {
        authenticatedUser = Mockito.mock(AuthenticatedUser.class);
        groupService = Mockito.mock(GroupService.class);
        rbacService = Mockito.mock(RbacService.class);
        requestScope = Mockito.mock(RequestScope.class);
        user = Mockito.mock(User.class);
        when(requestScope.getUser()).thenReturn(user);
    }

    @Test
    void testTeamManagePolicySet_SuperUserAlwaysAllowed() {
        TeamManagePolicySet check = new TeamManagePolicySet();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        PolicySet ps = new PolicySet();
        ps.setId(UUID.randomUUID());

        assertTrue(check.ok(ps, requestScope, Optional.empty()));
    }

    @Test
    void testTeamManagePolicySet_RbacAllowedAndDenied() {
        TeamManagePolicySet check = new TeamManagePolicySet();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(authenticatedUser.isServiceAccount(user)).thenReturn(false);

        Team team = new Team();
        team.setName("SecOps");

        Organization org = new Organization();
        org.setTeam(List.of(team));

        PolicySet ps = new PolicySet();
        ps.setOrganization(org);

        when(groupService.isMember(user, "SecOps")).thenReturn(true);
        when(rbacService.canManagePolicies(team)).thenReturn(true);

        assertTrue(check.ok(ps, requestScope, Optional.empty()));

        when(rbacService.canManagePolicies(team)).thenReturn(false);
        assertFalse(check.ok(ps, requestScope, Optional.empty()));
    }

    @Test
    void testTeamOverridePolicy_OverrideTeamMembership() {
        TeamOverridePolicy check = new TeamOverridePolicy();
        check.authenticatedUser = authenticatedUser;
        check.groupService = groupService;
        check.rbacService = rbacService;

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(authenticatedUser.isServiceAccount(user)).thenReturn(false);

        PolicySet ps = new PolicySet();
        ps.setOverrideTeam("Security-Approvers");

        Organization org = new Organization();
        org.setPolicySet(List.of(ps));

        io.terrakube.api.rs.job.Job job = new io.terrakube.api.rs.job.Job();
        job.setOrganization(org);

        PolicyEvaluation eval = new PolicyEvaluation();
        eval.setJob(job);

        PolicyOverride override = new PolicyOverride();
        override.setEvaluation(eval);

        when(groupService.isMember(user, "Security-Approvers")).thenReturn(true);
        assertTrue(check.ok(override, requestScope, Optional.empty()));

        when(groupService.isMember(user, "Security-Approvers")).thenReturn(false);
        assertFalse(check.ok(override, requestScope, Optional.empty()));
    }
}
