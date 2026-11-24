package io.terrakube.api.rs.hooks.workspace;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * Fills in what a new workspace's request left out from the defaults of its project. It runs before the flush,
 * because the Terraform version is a required column and the row is inserted at the flush. A value the request
 * sent always wins, and a project with no defaults changes nothing.
 */
public class WorkspaceProjectDefaultsHook implements LifeCycleHook<Workspace> {

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Workspace workspace, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        if (workspace.getProject() == null) {
            return;
        }
        ExecutionMode mode = ProjectWorkspaceDefaults.executionMode(workspace.getProject());
        if (workspace.getExecutionMode() == null && mode != null) {
            workspace.setExecutionMode(mode);
        }
        String template = ProjectWorkspaceDefaults.template(workspace.getProject());
        if ((workspace.getDefaultTemplate() == null || workspace.getDefaultTemplate().isBlank()) && template != null) {
            workspace.setDefaultTemplate(template);
        }
        String version = ProjectWorkspaceDefaults.terraformVersion(workspace.getProject());
        if ((workspace.getTerraformVersion() == null || workspace.getTerraformVersion().isBlank()) && version != null) {
            workspace.setTerraformVersion(version);
        }
    }
}
