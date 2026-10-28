package io.terrakube.api.rs.checks.tag;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageTag.RULE)
public class TeamManageTag extends OperationCheck<Tag> {

    public static final String RULE = "team manage tag";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RbacService rbacService;

    @Override
    public boolean ok(Tag tag, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (tag == null || tag.getOrganization() == null) {
            return false;
        }
        log.debug("team manage tag {}", tag.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        List<Team> teamList = tag.getOrganization().getTeam();
        return membershipService.checkTeamPermission(requestScope.getUser(), teamList, rbacService::canManageWorkspace);
    }
}

