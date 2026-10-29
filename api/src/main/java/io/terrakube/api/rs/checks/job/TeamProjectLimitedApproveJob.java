package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;

import java.util.List;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamProjectLimitedApproveJob.RULE)
public class TeamProjectLimitedApproveJob extends OperationCheck<Job> {
    public static final String RULE = "team project limited approve job";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    RbacService rbacService;

    @Autowired
    MembershipService membershipService;

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (job == null || job.getWorkspace() == null || job.getWorkspace().getOrganization() == null || job.getOrganization() == null) {
            log.warn("Job, workspace, or organization is null");
            return false;
        }
        if (!job.getOrganization().getId().equals(job.getWorkspace().getOrganization().getId())) {
            log.warn("Rejecting project limited approve job: job organization {} does not match workspace organization {}",
                    job.getOrganization().getId(), job.getWorkspace().getOrganization().getId());
            return false;
        }
        log.debug("team project limited approve job {}", job.getId());
        Project project = job.getWorkspace().getProject();
        if (project == null) return false;
        List<ProjectAccess> accessList = project.getProjectAccess();
        if (accessList == null || accessList.isEmpty()) return false;
        return membershipService.checkProjectMembership(
                requestScope.getUser(), accessList, pa -> rbacService.canApproveJob(pa));
    }
}
