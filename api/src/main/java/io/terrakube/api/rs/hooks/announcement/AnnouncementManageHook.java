package io.terrakube.api.rs.hooks.announcement;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScheduleType;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
public class AnnouncementManageHook implements LifeCycleHook<Announcement> {

    private static final int MAX_TITLE_LENGTH = 120;

    @Override
    public void execute(
            LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase,
            Announcement announcement,
            RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        if (transactionPhase != LifeCycleHookBinding.TransactionPhase.PRECOMMIT) {
            return;
        }
        if (operation != LifeCycleHookBinding.Operation.CREATE && operation != LifeCycleHookBinding.Operation.UPDATE) {
            return;
        }

        validate(announcement);

        if (operation == LifeCycleHookBinding.Operation.UPDATE) {
            announcement.setUpdatedDate(new Date());
        }
    }

    private void validate(Announcement announcement) {
        if (announcement == null) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "Announcement payload is required");
        }

        String title = announcement.getTitle();
        if (title == null || title.isBlank()) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "title is required");
        }
        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new AnnouncementValidationException(
                    HttpStatus.SC_BAD_REQUEST, "title must be at most " + MAX_TITLE_LENGTH + " characters");
        }

        if (announcement.getMessage() == null || announcement.getMessage().isBlank()) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "message is required");
        }
        if (announcement.getSeverity() == null) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "severity is required");
        }
        if (announcement.getScope() == null) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "scope is required");
        }
        if (announcement.getScheduleType() == null) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "scheduleType is required");
        }

        if (announcement.getScope() == AnnouncementScope.GLOBAL) {
            if (announcement.getOrganization() != null) {
                throw new AnnouncementValidationException(
                        HttpStatus.SC_BAD_REQUEST, "Global announcements cannot be linked to an organization");
            }
        } else if (announcement.getScope() == AnnouncementScope.ORGANIZATION) {
            if (announcement.getOrganization() == null) {
                throw new AnnouncementValidationException(
                        HttpStatus.SC_BAD_REQUEST, "Organization-scoped announcements must reference an organization");
            }
            if (announcement.isDisplayOnLogin()) {
                throw new AnnouncementValidationException(
                        HttpStatus.SC_BAD_REQUEST, "Only global announcements can be displayed on the login screen");
            }
        }

        Date startsAt = announcement.getStartsAt();
        Date endsAt = announcement.getEndsAt();
        if (announcement.getScheduleType() == AnnouncementScheduleType.SCHEDULED) {
            if (startsAt == null || endsAt == null) {
                throw new AnnouncementValidationException(
                        HttpStatus.SC_BAD_REQUEST, "Scheduled announcements require both startsAt and endsAt");
            }
            if (!endsAt.after(startsAt)) {
                throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "endsAt must be after startsAt");
            }
        } else if (startsAt != null && endsAt != null && !endsAt.after(startsAt)) {
            throw new AnnouncementValidationException(HttpStatus.SC_BAD_REQUEST, "endsAt must be after startsAt");
        }
    }
}
