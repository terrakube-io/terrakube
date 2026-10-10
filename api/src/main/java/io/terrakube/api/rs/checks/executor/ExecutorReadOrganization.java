package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@SecurityCheck(ExecutorReadOrganization.RULE)
public class ExecutorReadOrganization extends OperationCheck<Organization> {

    public static final String RULE = "executor read organization";

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Override
    public boolean ok(Organization organization, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (organization == null || organization.getId() == null) {
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

        Object organizationId = claims.get("organizationId");
        if (organizationId != null && organization.getId().toString().equals(String.valueOf(organizationId))) {
            log.debug("ExecutorReadOrganization granted via matching organizationId claim");
            return true;
        }

        try {
            Optional<Workspace> ws = workspaceRepository.findById(UUID.fromString(String.valueOf(workspaceId)));
            if (ws.isPresent() && ws.get().getOrganization() != null) {
                boolean match = ws.get().getOrganization().getId().equals(organization.getId());
                log.debug("ExecutorReadOrganization checked via workspace lookup: {}", match);
                return match;
            }
        } catch (Exception e) {
            log.warn("Failed to check workspace organization for executor: {}", e.getMessage());
        }

        return false;
    }
}
