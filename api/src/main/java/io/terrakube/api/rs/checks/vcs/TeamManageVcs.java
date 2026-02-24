package io.terrakube.api.rs.checks.vcs;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.vcs.Vcs;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageVcs.RULE)
public class TeamManageVcs extends OperationCheck<Vcs> {
    public static final String RULE = "team manage vcs";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Vcs vcs, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage vcs {}", vcs.getId());
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = vcs.getOrganization().getTeam();
        for (Team team : teamList) {
            if (isServiceAccount){
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageVcs(team) ){
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageVcs(team))
                    return true;
            }
        }
        return false;
    }
}
