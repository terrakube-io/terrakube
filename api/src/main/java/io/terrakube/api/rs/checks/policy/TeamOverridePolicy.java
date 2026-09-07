package io.terrakube.api.rs.checks.policy;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.policy.PolicyOverride;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamOverridePolicy.RULE)
public class TeamOverridePolicy extends OperationCheck<PolicyOverride> {

    public static final String RULE = "team override policy";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(PolicyOverride override, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team override policy {}", override.getId());
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (override.getEvaluation() == null || override.getEvaluation().getJob() == null
                || override.getEvaluation().getJob().getOrganization() == null) {
            return false;
        }

        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        List<PolicySet> policySets = override.getEvaluation().getJob().getOrganization().getPolicySet();

        // If any policy set defines an overrideTeam, check membership in that overrideTeam
        if (policySets != null) {
            for (PolicySet ps : policySets) {
                if (ps.getOverrideTeam() != null && !ps.getOverrideTeam().isBlank()) {
                    boolean isMember = isServiceAccount
                            ? groupService.isServiceMember(requestScope.getUser(), ps.getOverrideTeam())
                            : groupService.isMember(requestScope.getUser(), ps.getOverrideTeam());
                    if (isMember) {
                        return true;
                    }
                }
            }
        }

        // Fallback: check if user belongs to an org team with canManagePolicies permission
        List<Team> teamList = override.getEvaluation().getJob().getOrganization().getTeam();
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
