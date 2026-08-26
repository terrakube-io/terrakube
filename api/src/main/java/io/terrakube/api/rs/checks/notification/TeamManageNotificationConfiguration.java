package io.terrakube.api.rs.checks.notification;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.Optional;

/**
 * Mirrors Workspace's own three-tier update permission ("team manage workspace
 * OR team project limited manage workspace OR team limited manage workspace")
 * for workspace-scoped configurations, so anyone who can otherwise manage a
 * workspace - whether via blanket org-wide team access, a project-level grant,
 * or a workspace-level grant - can also manage its notifications. Org-scoped
 * configurations (no workspace) only have the org-wide tier to check against;
 * see TeamReadNotificationConfiguration for the more permissive rule that
 * governs read access to those org-wide rows.
 */
@Slf4j
@SecurityCheck(TeamManageNotificationConfiguration.RULE)
public class TeamManageNotificationConfiguration extends NotificationConfigurationAccessSupport {
    public static final String RULE = "team manage notification configuration";

    @Override
    public boolean ok(NotificationConfiguration configuration, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team manage notification configuration {}", configuration.getId());
        Workspace workspace = configuration.getWorkspace();
        Organization organization = workspace != null ? workspace.getOrganization() : configuration.getOrganization();

        if (hasOrgWideAccess(organization, requestScope)) {
            return true;
        }
        if (workspace == null) {
            return false;
        }
        return hasWorkspaceLevelAccess(workspace, requestScope) || hasProjectLevelAccess(workspace, requestScope);
    }
}
