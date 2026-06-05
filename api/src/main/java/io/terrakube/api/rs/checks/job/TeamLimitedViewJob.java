package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamLimitedViewJob.RULE)
public class TeamLimitedViewJob extends OperationCheck<Job> {
    public static final String RULE = "team limited view job";

    @Autowired
    MembershipService membershipService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view job {}", job.getId());
        if (job.getWorkspace() == null){
            log.warn("Workspace is null for job {} this should not happen", job.getId());
            return false;
        }
        List<Access> teamLimitedList = job.getWorkspace().getAccess();
        return authenticatedUser.isSuperUser(requestScope.getUser()) ? true : membershipService.checkLimitedMembership(requestScope.getUser(), teamLimitedList);

    }
}
