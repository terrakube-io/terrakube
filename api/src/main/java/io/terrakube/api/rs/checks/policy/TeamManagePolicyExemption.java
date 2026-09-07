package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.policy.PolicyExemption;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManagePolicyExemption.RULE)
public class TeamManagePolicyExemption extends OperationCheck<PolicyExemption> {

    public static final String RULE = "team manage policy_exemption";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(PolicyExemption exemption, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage policy_exemption {}", exemption.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<Team> teamList = exemption.getOrganization().getTeam();
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
        return false;
    }
}
