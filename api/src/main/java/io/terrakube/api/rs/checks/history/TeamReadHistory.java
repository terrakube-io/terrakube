package io.terrakube.api.rs.checks.history;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import lombok.AllArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.Set;

/** State-history visibility follows the owning workspace's manage-state grants. */
@SecurityCheck(TeamReadHistory.RULE)
@AllArgsConstructor
public class TeamReadHistory extends OperationCheck<History> {

    public static final String RULE = "team read history";

    private final AuthenticatedUser authenticatedUser;
    private final GroupService groupService;
    private final RbacService rbacService;

    @Override
    public boolean ok(History history, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (history == null || requestScope.getUser() == null
                || !(requestScope.getUser().getPrincipal() instanceof JwtAuthenticationToken jwt)) {
            return false;
        }
        // Internal tokens retain their existing, separate executor checks. Group claims must
        // never let a workspace runner or another internal component bypass those checks.
        if ("TerrakubeInternal".equals(jwt.getTokenAttributes().get("iss"))) {
            return false;
        }

        Workspace workspace = history.getWorkspace();
        if (workspace == null || workspace.getOrganization() == null) {
            return false;
        }
        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        Set<String> groups = groupService.getEffectiveGroups(requestScope.getUser());
        if (groups.isEmpty()) {
            return false;
        }
        return (workspace.getOrganization().getTeam() != null
                && workspace.getOrganization().getTeam().stream().anyMatch(team ->
                    groups.contains(team.getName()) && rbacService.canManageState(team)))
                || (workspace.getAccess() != null && workspace.getAccess().stream().anyMatch(access ->
                    groups.contains(access.getName()) && rbacService.canManageState(access)))
                || (workspace.getProject() != null && workspace.getProject().getProjectAccess() != null
                    && workspace.getProject().getProjectAccess().stream().anyMatch(access ->
                        groups.contains(access.getName()) && rbacService.canManageState(access)));
    }
}
