package io.terrakube.api.rs.hooks.announcement;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnouncementManageHookTest {

    private AnnouncementManageHook hook;
    private RequestScope requestScope;

    @BeforeEach
    void setUp() {
        hook = new AnnouncementManageHook();
        requestScope = Mockito.mock(RequestScope.class);
    }

    @Test
    void testValidGlobalAnnouncement_Passes() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Global Maintenance");
        announcement.setMessage("We are upgrading our systems.");
        announcement.setSeverity(AnnouncementSeverity.info);
        announcement.setScope(AnnouncementScope.GLOBAL);
        announcement.setDisplayOnLogin(true);

        assertDoesNotThrow(() -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testValidOrgAnnouncement_Passes() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Org Advisory");
        announcement.setMessage("Rotate AWS keys");
        announcement.setSeverity(AnnouncementSeverity.warning);
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        assertDoesNotThrow(() -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testEmptyTitle_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("   ");
        announcement.setMessage("Some message");
        announcement.setScope(AnnouncementScope.GLOBAL);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testTitleTooLong_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("a".repeat(121));
        announcement.setMessage("Some message");
        announcement.setScope(AnnouncementScope.GLOBAL);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testEmptyMessage_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Title");
        announcement.setMessage("");
        announcement.setScope(AnnouncementScope.GLOBAL);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testOrgScopeWithoutOrg_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Title");
        announcement.setMessage("Message");
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        announcement.setOrganization(null);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testGlobalScopeWithOrg_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Title");
        announcement.setMessage("Message");
        announcement.setScope(AnnouncementScope.GLOBAL);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testDisplayOnLoginWithOrgScope_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Title");
        announcement.setMessage("Message");
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        announcement.setOrganization(org);
        announcement.setDisplayOnLogin(true);

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }

    @Test
    void testStartsAtAfterEndsAt_ThrowsException() {
        Announcement announcement = new Announcement();
        announcement.setTitle("Title");
        announcement.setMessage("Message");
        announcement.setScope(AnnouncementScope.GLOBAL);
        announcement.setStartsAt(new Date(System.currentTimeMillis() + 100000));
        announcement.setEndsAt(new Date(System.currentTimeMillis()));

        assertThrows(AnnouncementManagementException.class, () -> hook.execute(
                LifeCycleHookBinding.Operation.CREATE,
                LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
                announcement,
                requestScope,
                Optional.empty()
        ));
    }
}

