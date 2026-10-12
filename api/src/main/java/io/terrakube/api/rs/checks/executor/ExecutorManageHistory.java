package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.history.History;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

@Slf4j
@SecurityCheck(ExecutorManageHistory.RULE)
public class ExecutorManageHistory extends OperationCheck<History> {

    public static final String RULE = "executor manage history";

    @Override
    public boolean ok(History history, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (history == null) {
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
        if (workspaceId == null || history.getWorkspace() == null || history.getWorkspace().getId() == null) {
            return false;
        }

        if (!history.getWorkspace().getId().toString().equals(String.valueOf(workspaceId))) {
            return false;
        }

        Object organizationId = claims.get("organizationId");
        if (organizationId != null && history.getWorkspace().getOrganization() != null && history.getWorkspace().getOrganization().getId() != null) {
            if (!history.getWorkspace().getOrganization().getId().toString().equals(String.valueOf(organizationId))) {
                return false;
            }
        }

        Object jobId = claims.get("jobId");
        if (jobId != null && history.getJobReference() != null && !history.getJobReference().isBlank()) {
            if (!history.getJobReference().equals(String.valueOf(jobId))) {
                return false;
            }
        }

        log.debug("ExecutorManageHistory granted for workspace {}", workspaceId);
        return true;
    }
}
