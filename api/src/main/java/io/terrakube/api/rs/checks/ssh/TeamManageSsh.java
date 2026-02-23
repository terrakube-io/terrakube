package io.terrakube.api.rs.checks.ssh;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.team.Team;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageSsh.RULE)
public class TeamManageSsh extends OperationCheck<Ssh> {
    public static final String RULE = "team manage ssh";

    @Autowired
    GroupService groupService;
    @Autowired
    AuthenticatedUser authenticatedUser;
    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Ssh ssh, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage ssh {}", ssh.getId());
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Team team : ssh.getOrganization().getTeam()) {
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
