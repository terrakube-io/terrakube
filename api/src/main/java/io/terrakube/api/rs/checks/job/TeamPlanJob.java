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
 * Checks whether the user has permission to queue/plan a job (create a run).
 * Requires the "plan" permission (admin/write/plan roles, or custom with planJob=true),
 * which is less restrictive than the "approve" permission needed for apply.
 */
@Slf4j
@SecurityCheck(TeamPlanJob.RULE)
public class TeamPlanJob extends OperationCheck<Job> {
    public static final String RULE = "team plan job";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team plan job {}", job.getId());
        List<Team> teamList = job.getOrganization().getTeam();
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Team team : teamList) {
            if (isServiceAccount) {
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canPlanJob(team)) {
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canPlanJob(team))
                    return true;
            }
        }
        return false;
    }
}
