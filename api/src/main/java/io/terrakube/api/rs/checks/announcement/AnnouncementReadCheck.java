package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
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
    MembershipService membershipService;

    @Autowired
    GroupService groupService;

    @Override
    public boolean ok(Announcement announcement, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (announcement == null || requestScope == null) {
            return false;
        }
        User user = requestScope.getUser();
        if (AnnouncementCheckSupport.isInternalOrUnauthenticated(user)) {
            return false;
        }
        if (announcement.getScope() == AnnouncementScope.GLOBAL) {
            return true;
        }
        if (announcement.getScope() == AnnouncementScope.ORGANIZATION) {
            Organization org = announcement.getOrganization();
            if (org == null) {
                return false;
            }
            if (authenticatedUser.isSuperUser(user)) {
                return true;
            }
            return membershipService.checkMembership(user, org.getTeam())
                    || groupService.isMemberWithLimitedAccessV2(user, org)
                    || groupService.isMemberWithProjectAccess(user, org);
        }
        return false;
    }
}
