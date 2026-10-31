package io.terrakube.api.rs.checks.job.address;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.project.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

@Slf4j
@SecurityCheck(TeamViewJobAddress.RULE)
public class TeamViewJobAddress extends OperationCheck<Address> {

    public static final String RULE = "team view job address";

    @Autowired
    private AuthenticatedUser authenticatedUser;

    @Autowired
    private MembershipService membershipService;

    @Override
    public boolean ok(Address address, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (address == null || address.getJob() == null) {
            return false;
        }
        log.debug("team view job address {}", address.getId());
        Job job = address.getJob();
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }
        if (isScopedExecutorForJob(job, requestScope)) {
            return true;
        }

        // 1. Organization tier
        if (job.getOrganization() != null && job.getOrganization().getTeam() != null) {
            if (membershipService.checkMembership(requestScope.getUser(), job.getOrganization().getTeam())) {
                return true;
            }
        }

        // 2. Workspace tier (direct Access bindings)
        if (job.getWorkspace() != null) {
            if (job.getWorkspace().getAccess() != null
                    && membershipService.checkLimitedMembership(requestScope.getUser(), job.getWorkspace().getAccess())) {
                return true;
            }

            // 3. Project tier
            Project project = job.getWorkspace().getProject();
            if (project != null && project.getProjectAccess() != null && !project.getProjectAccess().isEmpty()) {
                return membershipService.checkProjectMembership(
                        requestScope.getUser(), project.getProjectAccess(), pa -> true);
            }
        }

        return false;
    }

    private boolean isScopedExecutorForJob(Job job, RequestScope requestScope) {
        if (requestScope.getUser() == null || !(requestScope.getUser().getPrincipal() instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        Map<String, Object> claims = jwt.getTokenAttributes();
        if (InternalTokenClassifier.classify(claims) != InternalTokenType.WORKSPACE_EXECUTOR) {
            return false;
        }

        Object jobId = claims.get("jobId");
        if (jobId == null || !String.valueOf(job.getId()).equals(String.valueOf(jobId))) {
            return false;
        }

        Object workspaceId = claims.get("workspaceId");
        if (workspaceId == null || job.getWorkspace() == null || job.getWorkspace().getId() == null) {
            return false;
        }

        if (!job.getWorkspace().getId().toString().equals(String.valueOf(workspaceId))) {
            return false;
        }

        Object organizationId = claims.get("organizationId");
        if (organizationId != null && job.getOrganization() != null && job.getOrganization().getId() != null) {
            if (!job.getOrganization().getId().toString().equals(String.valueOf(organizationId))) {
                return false;
            }
        }

        return true;
    }
}

