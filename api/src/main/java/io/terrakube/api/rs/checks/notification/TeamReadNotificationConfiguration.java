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
 * Read access to NotificationConfiguration rows. Workspace-scoped rows use the same tiers as
 * TeamManageNotificationConfiguration. Org-wide default rows (no workspace) are additionally
 * readable by anyone who manages at least one workspace in that organization - not just org-wide
 * managers - because NotificationConfigResolver applies no permission filter of its own: an
 * org-wide default notification fires for every workspace in the org regardless of who can see
 * it here, so a workspace/project-level manager who can't see it would otherwise be misled into
 * thinking no organization-wide defaults apply to a workspace they manage.
 */
@Slf4j
@SecurityCheck(TeamReadNotificationConfiguration.RULE)
public class TeamReadNotificationConfiguration extends NotificationConfigurationAccessSupport {
    public static final String RULE = "team read notification configuration";

    @Override
    public boolean ok(NotificationConfiguration configuration, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.debug("team read notification configuration {}", configuration.getId());
        Workspace workspace = configuration.getWorkspace();
        Organization organization = workspace != null ? workspace.getOrganization() : configuration.getOrganization();

        if (hasOrgWideAccess(organization, requestScope)) {
            return true;
        }
        if (workspace != null) {
            return hasWorkspaceLevelAccess(workspace, requestScope) || hasProjectLevelAccess(workspace, requestScope);
        }
        return canManageAtLeastOneWorkspaceIn(organization, requestScope);
    }
}
