package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

@Slf4j
@SecurityCheck(ExecutorReadWorkspace.RULE)
public class ExecutorReadWorkspace extends OperationCheck<Workspace> {

    public static final String RULE = "executor read workspace";

    @Override
    public boolean ok(Workspace workspace, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (workspace == null || workspace.getId() == null) {
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
        if (workspaceId == null) {
            return false;
        }

        if (!workspace.getId().toString().equals(String.valueOf(workspaceId))) {
            return false;
        }

        Object organizationId = claims.get("organizationId");
        if (organizationId != null && workspace.getOrganization() != null && workspace.getOrganization().getId() != null) {
            if (!workspace.getOrganization().getId().toString().equals(String.valueOf(organizationId))) {
                return false;
            }
        }

        log.debug("ExecutorReadWorkspace granted for workspace {}", workspace.getId());
        return true;
    }
}
