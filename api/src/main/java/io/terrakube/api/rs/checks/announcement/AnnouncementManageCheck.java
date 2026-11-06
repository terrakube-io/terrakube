package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.checks.membership.MembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(AnnouncementManageCheck.RULE)
public class AnnouncementManageCheck extends OperationCheck<Announcement> {

    public static final String RULE = "announcement manage check";

    private static final String ROLE_ADMIN = "admin";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Announcement announcement, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (announcement == null) {
            return true;
        }

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        if (announcement.getScope() == AnnouncementScope.GLOBAL) {
            // Strictly Platform Superuser
            return false;
        }

        if (announcement.getScope() == AnnouncementScope.ORGANIZATION) {
            if (announcement.getOrganization() == null) {
                return false;
            }
            return membershipService.checkTeamPermission(
                    requestScope.getUser(),
                    announcement.getOrganization().getTeam(),
                    team -> ROLE_ADMIN.equalsIgnoreCase(team.getRole())
            );
        }

        return false;
    }
}

