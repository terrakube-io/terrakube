package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.checks.membership.MembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(AnnouncementReadCheck.RULE)
public class AnnouncementReadCheck extends OperationCheck<Announcement> {

    public static final String RULE = "announcement read check";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Announcement announcement, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (announcement == null) {
            return true;
        }

        if (announcement.getScope() == AnnouncementScope.GLOBAL) {
            return true;
        }

        if (announcement.getScope() == AnnouncementScope.ORGANIZATION) {
            if (announcement.getOrganization() == null) {
                return false;
            }
            if (authenticatedUser.isSuperUser(requestScope.getUser())) {
                return true;
            }
            if (membershipService.checkMembership(requestScope.getUser(), announcement.getOrganization().getTeam())) {
                return true;
            }
            if (groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), announcement.getOrganization())) {
                return true;
            }
            return groupService.isMemberWithProjectAccess(requestScope.getUser(), announcement.getOrganization());
        }

        return false;
    }
}

