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
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;

/**
 * Workspace-level (limited access) check for plan permission.
 * Checks whether the user has plan permission via workspace-level Access entries.
 */
@Slf4j
@SecurityCheck(TeamLimitedPlanJob.RULE)
public class TeamLimitedPlanJob extends OperationCheck<Job> {
    public static final String RULE = "team limited plan job";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited plan job {}", job.getId());
        List<Access> teamList = job.getWorkspace().getAccess();
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        if (!teamList.isEmpty())
            for (Access team : teamList) {
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
