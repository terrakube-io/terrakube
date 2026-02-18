package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.team.Team;

import java.util.List;
import java.util.Optional;

/**
 * Organization-level RBAC check for approve/apply permission.
 * <p>
 * Unlike {@link TeamApproveJob} which checks membership in a specific
 * approval team (set by the TCL flow), this check validates whether
 * the user's team has the general "approve job" RBAC permission.
 * <p>
 * Requires the "approve" permission specifically (admin/write roles,
 * or custom with approveJob=true). The "plan" role does NOT have
 * approve permission — this is the key separation from plan-only access.
 */
@Slf4j
@SecurityCheck(TeamApproveJobRbac.RULE)
public class TeamApproveJobRbac extends OperationCheck<Job> {
    public static final String RULE = "team approve job rbac";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team approve job rbac {}", job.getId());
        List<Team> teamList = job.getOrganization().getTeam();
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Team team : teamList) {
            if (isServiceAccount) {
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canApproveJob(team)) {
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canApproveJob(team))
                    return true;
            }
        }
        return false;
    }
}
