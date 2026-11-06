package io.terrakube.api.rs.hooks.announcement;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;

import java.util.Optional;

public class AnnouncementManageHook implements LifeCycleHook<Announcement> {

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
                        LifeCycleHookBinding.TransactionPhase phase,
                        Announcement announcement,
                        RequestScope requestScope,
                        Optional<ChangeSpec> changes) {

        if (announcement.getTitle() == null || announcement.getTitle().trim().isEmpty()) {
            throw new AnnouncementManagementException(400, "Announcement title cannot be empty");
        }
        if (announcement.getTitle().length() > 120) {
            throw new AnnouncementManagementException(400, "Announcement title cannot exceed 120 characters");
        }
        if (announcement.getMessage() == null || announcement.getMessage().trim().isEmpty()) {
            throw new AnnouncementManagementException(400, "Announcement message cannot be empty");
        }
        if (announcement.getScope() == null) {
            throw new AnnouncementManagementException(400, "Announcement scope cannot be null");
        }
        if (announcement.getScope() == AnnouncementScope.ORGANIZATION && announcement.getOrganization() == null) {
            throw new AnnouncementManagementException(400, "Organization-scoped announcement must specify an organization");
        }
        if (announcement.getScope() == AnnouncementScope.GLOBAL && announcement.getOrganization() != null) {
            throw new AnnouncementManagementException(400, "Global announcement cannot be linked to a specific organization");
        }
        if (announcement.isDisplayOnLogin() && announcement.getScope() != AnnouncementScope.GLOBAL) {
            throw new AnnouncementManagementException(400, "displayOnLogin is only valid for GLOBAL scope announcements");
        }
        if (announcement.getStartsAt() != null && announcement.getEndsAt() != null) {
            if (!announcement.getStartsAt().before(announcement.getEndsAt())) {
                throw new AnnouncementManagementException(400, "startsAt must be before endsAt");
            }
        }
    }
}

