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

@Slf4j
@SecurityCheck(TeamManageJob.RULE)
public class TeamManageJob extends OperationCheck<Job> {
    public static final String RULE = "team manage job";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    GroupService groupService;

    @Autowired
    RbacService rbacService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (job == null || job.getWorkspace() == null || job.getWorkspace().getOrganization() == null || job.getOrganization() == null) {
            log.warn("Job, workspace, or organization is null");
            return false;
        }
        if (!job.getOrganization().getId().equals(job.getWorkspace().getOrganization().getId())) {
            log.warn("Rejecting manage job: job organization {} does not match workspace organization {}",
                    job.getOrganization().getId(), job.getWorkspace().getOrganization().getId());
            return false;
        }
        log.debug("team manage job {}", job.getId());
        List<Team> teamList = job.getWorkspace().getOrganization().getTeam();
        boolean isServiceAccount = authenticatedUser.isServiceAccount(requestScope.getUser());
        for (Team team : teamList) {
            if (isServiceAccount){
                if (groupService.isServiceMember(requestScope.getUser(), team.getName()) && rbacService.canManageJob(team)){
                    return true;
                }
            } else {
                if (groupService.isMember(requestScope.getUser(), team.getName()) && rbacService.canManageJob(team))
                    return true;
            }
        }
        return false;
    }
}
