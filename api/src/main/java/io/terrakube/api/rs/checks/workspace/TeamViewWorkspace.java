package io.terrakube.api.rs.checks.workspace;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewWorkspace.RULE)
public class TeamViewWorkspace extends OperationCheck<Workspace> {

    public static final String RULE = "team view workspace";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view workspace {}", workspace.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) return true;
        List<Team> teamList = workspace.getOrganization().getTeam();
        if (workspace.getProject() == null) {
            return membershipService.checkMembership(requestScope.getUser(), teamList);
        }
        for (Team team : teamList) {
            if (authenticatedUser.isServiceAccount(requestScope.getUser())) {
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team))
                    return true;
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageWorkspace(team))
                    return true;
            }
        }
        return false;
    }

}
