package io.terrakube.api.rs.checks.tag;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewTag.RULE)
public class TeamViewTag extends OperationCheck<Tag> {

    public static final String RULE = "team view tag";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private GroupService groupService;

    @Override
    public boolean ok(Tag tag, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (tag == null || tag.getOrganization() == null) {
            return false;
        }
        log.debug("team view tag {}", tag.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = tag.getOrganization().getTeam();
        if (membershipService.checkMembership(requestScope.getUser(), teamList)) {
            return true;
        }
        if (groupService.isMemberWithLimitedAccessV2(requestScope.getUser(), tag.getOrganization())) {
            return true;
        }
        return groupService.isMemberWithProjectAccess(requestScope.getUser(), tag.getOrganization());
    }
}

