package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.policy.PolicySetParameter;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManagePolicySetParameter.RULE)
public class TeamManagePolicySetParameter extends OperationCheck<PolicySetParameter> {

    public static final String RULE = "team manage policy set parameter";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(PolicySetParameter parameter, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage policy set parameter {}", parameter.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        if (parameter.getPolicySet() != null && parameter.getPolicySet().getOrganization() != null) {
            List<Team> teamList = parameter.getPolicySet().getOrganization().getTeam();
            if (teamList != null) {
                for (Team team : teamList) {
                    if (isServiceAccount) {
                        if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManagePolicies(team)) {
                            return true;
                        }
                    } else {
                        if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManagePolicies(team)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
