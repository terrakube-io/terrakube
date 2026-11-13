package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class JobRunRightsTest {
    private final AuthenticatedUser authenticatedUser = Mockito.mock(AuthenticatedUser.class);
    private final JobRunRights rights = new JobRunRights(authenticatedUser, Mockito.mock(GroupService.class),
            Mockito.mock(RbacService.class), Mockito.mock(MembershipService.class));
    private final User user = new User(null);

    @Test
    void anInternalMachineTokenGetsNoPassLikeEverywhereElse() {
        when(authenticatedUser.isServiceAccountInternal(user)).thenReturn(true);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        assertFalse(rights.canDestroy(user, new Workspace()));
        assertFalse(rights.canApprove(user, new Workspace()));
    }

    @Test
    void aSuperUserCanDoBoth() {
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        assertTrue(rights.canDestroy(user, new Workspace()));
        assertTrue(rights.canApprove(user, new Workspace()));
    }

    @Test
    void aWorkspaceThatIsGoneIsNotAllowedAndTheMessagesDoNotFail() {
        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        assertFalse(rights.canDestroy(user, null));
        assertTrue(JobRunRights.destroyRefusal(null).contains("(deleted workspace)"));
        assertTrue(JobRunRights.applyRefusal(null).contains("(deleted workspace)"));
        assertTrue(JobRunRights.approveDestroyRefusal(null).contains("(deleted workspace)"));
    }
}
