package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

@Slf4j
@SecurityCheck(ExecutorManageJob.RULE)
public class ExecutorManageJob extends OperationCheck<Job> {

    public static final String RULE = "executor manage job";

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (job == null) {
            return false;
        }

        if (requestScope.getUser() == null || !(requestScope.getUser().getPrincipal() instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        Map<String, Object> claims = jwt.getTokenAttributes();
        if (!"TerrakubeInternal".equals(claims.get("iss"))) {
            return false;
        }

        Object workspaceId = claims.get("workspaceId");
        if (workspaceId == null || job.getWorkspace() == null || job.getWorkspace().getId() == null) {
            return false;
        }

        if (!job.getWorkspace().getId().toString().equals(String.valueOf(workspaceId))) {
            return false;
        }

        Object jobId = claims.get("jobId");
        if (jobId == null || !String.valueOf(job.getId()).equals(String.valueOf(jobId))) {
            return false;
        }

        Object organizationId = claims.get("organizationId");
        if (organizationId != null && job.getOrganization() != null && job.getOrganization().getId() != null) {
            if (!job.getOrganization().getId().toString().equals(String.valueOf(organizationId))) {
                return false;
            }
        }

        log.debug("ExecutorManageJob granted for job {}", job.getId());
        return true;
    }
}
