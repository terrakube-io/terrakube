package io.terrakube.api.rs.checks.job.step;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.access.Access;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamLimitedViewJobStep.RULE)
public class TeamLimitedViewJobStep extends OperationCheck<Step> {
    public static final String RULE = "team limited view job step";

    @Autowired
    MembershipService membershipService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Override
    public boolean ok(Step step, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team limited view job step {}", step.getId());
        Job job = step.getJob();
        if (job == null || job.getWorkspace() == null) {
            log.warn("Job or workspace is null for step {}", step.getId());
            return false;
        }
        List<Access> teamLimitedList = job.getWorkspace().getAccess();
        return authenticatedUser.isSuperUser(requestScope.getUser()) ? true : membershipService.checkLimitedMembership(requestScope.getUser(), teamLimitedList);
    }
}
