package io.terrakube.api.rs.checks.job.step;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.team.Team;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewJobStep.RULE)
public class TeamViewJobStep extends OperationCheck<Step> {
    public static final String RULE = "team view job step";

    @Autowired
    MembershipService membershipService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Override
    public boolean ok(Step step, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team view job step {}", step.getId());
        Job job = step.getJob();
        if (job == null || job.getOrganization() == null) {
            log.warn("Job or organization is null for step {}", step.getId());
            return false;
        }
        List<Team> teamList = job.getOrganization().getTeam();
        return authenticatedUser.isSuperUser(requestScope.getUser()) ? true : membershipService.checkMembership(requestScope.getUser(), teamList);
    }
}
