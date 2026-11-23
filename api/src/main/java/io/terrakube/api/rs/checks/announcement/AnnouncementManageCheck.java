package io.terrakube.api.rs.checks.announcement;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.User;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.Organization;
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

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    MembershipService membershipService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Announcement announcement, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (announcement == null || requestScope == null) {
            return false;
        }
        User user = requestScope.getUser();
        if (AnnouncementCheckSupport.isInternalOrUnauthenticated(user)) {
            return false;
        }
        if (authenticatedUser.isSuperUser(user)) {
            return true;
        }
        if (announcement.getScope() != AnnouncementScope.ORGANIZATION) {
            return false;
        }
        Organization org = announcement.getOrganization();
        if (org == null) {
            return false;
        }
        return membershipService.checkTeamPermission(user, org.getTeam(), rbacService::canManageAnnouncements);
    }
}
