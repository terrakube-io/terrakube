package io.terrakube.api.rs.checks.trigger;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * Creating or changing a run trigger requires manage rights on the <strong>destination</strong>
 * workspace - the one whose runs start firing - plus visibility of the <strong>source</strong>.
 * Without the second half, anyone with rights on a single workspace could attach it to any
 * other workspace in the organization and learn when that one applies.
 *
 * Deletion is deliberately governed by {@link TeamDeleteWorkspaceTrigger} instead, so that
 * losing access to the source cannot strand a trigger on a workspace you own.
 *
 * Permissions are enforced when the edge is configured, not on every dispatch.
 */
@Slf4j
@SecurityCheck(TeamManageWorkspaceTrigger.RULE)
public class TeamManageWorkspaceTrigger extends OperationCheck<WorkspaceRunTrigger> {

    public static final String RULE = "team manage workspace trigger";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Autowired
    WorkspaceTriggerPermissions permissions;

    @Override
    public boolean ok(WorkspaceRunTrigger trigger, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        log.debug("team manage workspace trigger {}", trigger.getId());

        if (!isStructurallyValid(trigger)) {
            return false;
        }

        if (authenticatedUser.isSuperUser(requestScope.getUser())) {
            return true;
        }

        return permissions.canManage(trigger.getDestinationWorkspace(), requestScope)
                && permissions.canView(trigger.getSourceWorkspace(), requestScope);
    }

    /**
     * Invariants that hold regardless of who is asking. Checked before the superuser
     * shortcut on purpose: a self-loop or a cross-tenant edge is malformed data, not a
     * privilege question, and a superuser should not be able to create one either.
     */
    private boolean isStructurallyValid(WorkspaceRunTrigger trigger) {
        Workspace destination = trigger.getDestinationWorkspace();
        Workspace source = trigger.getSourceWorkspace();

        if (destination == null || source == null) {
            return false;
        }

        // A workspace triggering itself would re-run on every apply until the cascade limit.
        if (destination.getId().equals(source.getId())) {
            log.warn("Rejecting run trigger: workspace {} cannot trigger itself", destination.getId());
            return false;
        }

        if (destination.getOrganization() == null || source.getOrganization() == null
                || !destination.getOrganization().getId().equals(source.getOrganization().getId())) {
            log.warn("Rejecting run trigger: source and destination belong to different organizations");
            return false;
        }

        // The template override runs inside the destination's context, so a template from
        // another organization would mean executing foreign TCL against these workspaces.
        if (trigger.getTemplate() != null && trigger.getTemplate().getOrganization() != null
                && !trigger.getTemplate().getOrganization().getId()
                        .equals(destination.getOrganization().getId())) {
            log.warn("Rejecting run trigger: template belongs to a different organization");
            return false;
        }

        return true;
    }
}
