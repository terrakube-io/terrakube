package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AnnouncementSecurityChecksTest {

    private AuthenticatedUser authenticatedUser;
    private GroupService groupService;
    private MembershipService membershipService;
    private RequestScope requestScope;
    private User user;

    private AnnouncementReadCheck readCheck;
    private AnnouncementManageCheck manageCheck;

    @BeforeEach
    void setUp() {
        authenticatedUser = Mockito.mock(AuthenticatedUser.class);
        groupService = Mockito.mock(GroupService.class);
        membershipService = Mockito.mock(MembershipService.class);
        requestScope = Mockito.mock(RequestScope.class);
        user = Mockito.mock(User.class);
        when(requestScope.getUser()).thenReturn(user);

        readCheck = new AnnouncementReadCheck();
        readCheck.authenticatedUser = authenticatedUser;
        readCheck.groupService = groupService;
        readCheck.membershipService = membershipService;

        manageCheck = new AnnouncementManageCheck();
        manageCheck.authenticatedUser = authenticatedUser;
        manageCheck.membershipService = membershipService;
    }

    @Test
    void testReadCheck_NullEntity_Allowed() {
        assertTrue(readCheck.ok(null, requestScope, Optional.empty()));
    }

    @Test
    void testReadCheck_GlobalScope_AlwaysAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.GLOBAL);

        assertTrue(readCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testReadCheck_OrgScope_SuperUserAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        assertTrue(readCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testReadCheck_OrgScope_OrgMemberAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), any())).thenReturn(true);

        assertTrue(readCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testReadCheck_OrgScope_NonMemberDenied() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), any())).thenReturn(false);
        when(groupService.isMemberWithLimitedAccessV2(user, org)).thenReturn(false);
        when(groupService.isMemberWithProjectAccess(user, org)).thenReturn(false);

        assertFalse(readCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testReadCheck_OrgScope_MissingOrgDenied() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        announcement.setOrganization(null);

        assertFalse(readCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_NullEntity_Allowed() {
        assertTrue(manageCheck.ok(null, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_GlobalScope_SuperUserAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.GLOBAL);

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        assertTrue(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_GlobalScope_NonSuperUserDenied() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.GLOBAL);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        assertFalse(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_OrgScope_SuperUserAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(true);

        assertTrue(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_OrgScope_OrgAdminAllowed() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        Team adminTeam = new Team();
        adminTeam.setRole("admin");
        adminTeam.setName("ADMIN_TEAM");
        org.setTeam(List.of(adminTeam));
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkTeamPermission(eq(user), eq(org.getTeam()), any())).thenAnswer(invocation -> {
            Predicate<Team> pred = invocation.getArgument(2);
            return pred.test(adminTeam);
        });

        assertTrue(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_OrgScope_NonAdminOrgMemberDenied() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        Team writeTeam = new Team();
        writeTeam.setRole("write");
        writeTeam.setName("DEV_TEAM");
        org.setTeam(List.of(writeTeam));
        announcement.setOrganization(org);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkTeamPermission(eq(user), eq(org.getTeam()), any())).thenAnswer(invocation -> {
            Predicate<Team> pred = invocation.getArgument(2);
            return pred.test(writeTeam);
        });

        assertFalse(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }

    @Test
    void testManageCheck_OrgScope_MissingOrgDenied() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        announcement.setOrganization(null);

        when(authenticatedUser.isSuperUser(user)).thenReturn(false);

        assertFalse(manageCheck.ok(announcement, requestScope, Optional.empty()));
    }
}

