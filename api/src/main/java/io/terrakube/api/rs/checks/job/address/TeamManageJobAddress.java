package io.terrakube.api.rs.checks.job.address;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.project.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

@Slf4j
@SecurityCheck(TeamManageJobAddress.RULE)
public class TeamManageJobAddress extends OperationCheck<Address> {

    public static final String RULE = "team manage job address";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private RbacService rbacService;

    @Override
    public boolean ok(Address address, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (address == null || address.getJob() == null) {
            return false;
        }
        log.debug("team manage job address {}", address.getId());

        Job job = address.getJob();
        // Addresses can only be created, modified, or deleted while the job is still pending.
        if (job.getStatus() == null || !JobStatus.pending.equals(job.getStatus())) {
            log.warn("Cannot manage address for job {} in status {}", job.getId(), job.getStatus());
            return false;
        }

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        // 1. Organization tier
        if (job.getOrganization() != null && job.getOrganization().getTeam() != null) {
            if (membershipService.checkTeamPermission(requestScope.getUser(), job.getOrganization().getTeam(),
                    rbacService::canPlanJob)) {
                return true;
            }
        }

        // 2. Workspace tier (direct Access bindings)
        if (job.getWorkspace() != null) {
            if (job.getWorkspace().getAccess() != null
                    && membershipService.checkAccessPermission(requestScope.getUser(), job.getWorkspace().getAccess(),
                    rbacService::canPlanJob)) {
                return true;
            }

            // 3. Project tier
            Project project = job.getWorkspace().getProject();
            if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
                return membershipService.checkProjectMembership(
                        requestScope.getUser(), project.getProjectAccess(),
                        rbacService::canPlanJob);
            }
        }

        return false;
    }
}

