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
import io.terrakube.api.rs.checks.workspace.WorkspaceCheckSupport;
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

    @Autowired
    WorkspaceCheckSupport workspaceCheckSupport;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (job == null || job.getWorkspace() == null || job.getWorkspace().getOrganization() == null || job.getOrganization() == null) {
            log.warn("Job, workspace, or organization is null");
            return false;
        }
        if (!job.getOrganization().getId().equals(job.getWorkspace().getOrganization().getId())) {
            log.warn("Rejecting plan job: job organization {} does not match workspace organization {}",
                    job.getOrganization().getId(), job.getWorkspace().getOrganization().getId());
            return false;
        }
        log.debug("team plan job {}", job.getId());
        if (!workspaceCheckSupport.isTemplateInJobOrganization(job)) {
            log.warn("Rejecting plan job: templateReference does not belong to job organization");
            return false;
        }
        List<Team> teamList = job.getWorkspace().getOrganization().getTeam();
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
