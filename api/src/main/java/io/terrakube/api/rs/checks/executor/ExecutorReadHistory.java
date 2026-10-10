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
@SecurityCheck(ExecutorReadHistory.RULE)
public class ExecutorReadHistory extends OperationCheck<History> {

    public static final String RULE = "executor read history";

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

        boolean allowed = history.getWorkspace().getId().toString().equals(String.valueOf(workspaceId));
        log.debug("ExecutorReadHistory for workspace {} allowed: {}", workspaceId, allowed);
        return allowed;
    }
}
