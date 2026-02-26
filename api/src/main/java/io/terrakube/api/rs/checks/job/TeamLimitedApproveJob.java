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
 * Workspace-level (limited access) check for approve/apply permission.
 * <p>
 * Checks whether the user has approve permission via workspace-level Access entries.
 * Requires the "approve" permission specifically — the "plan" role does NOT pass this check.
 */
@Slf4j
@SecurityCheck(TeamLimitedApproveJob.RULE)
public class TeamLimitedApproveJob extends OperationCheck<Job> {
    public static final String RULE = "team limited approve job";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited approve job {}", job.getId());
        List<Access> teamList = job.getWorkspace().getAccess();
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        if (!teamList.isEmpty())
            for (Access team : teamList) {
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
