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
        if (job == null || job.getWorkspace() == null || job.getWorkspace().getOrganization() == null || job.getOrganization() == null) {
            log.warn("Job, workspace, or organization is null");
            return false;
        }
        if (!job.getOrganization().getId().equals(job.getWorkspace().getOrganization().getId())) {
            log.warn("Rejecting limited view job: job organization {} does not match workspace organization {}",
                    job.getOrganization().getId(), job.getWorkspace().getOrganization().getId());
            return false;
        }
        log.debug("team limited view job {}", job.getId());
        List<Access> teamLimitedList = job.getWorkspace().getAccess();
        return authenticatedUser.isSuperUser(requestScope.getUser()) ? true : membershipService.checkLimitedMembership(requestScope.getUser(), teamLimitedList);

    }
}
