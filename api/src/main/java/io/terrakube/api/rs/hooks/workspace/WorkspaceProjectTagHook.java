package io.terrakube.api.rs.hooks.workspace;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.project.ProjectDefaultTagService;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Copies a project's default tags onto a workspace once the workspace has been created in the project or moved into
 * it. It runs after the commit so the workspace row exists, and it is idempotent: a tag the workspace already has is
 * left alone, including its value. It never removes tags, and a failure only logs, because the move has already
 * been committed and must not be reported as failed.
 */
@AllArgsConstructor
@Slf4j
public class WorkspaceProjectTagHook implements LifeCycleHook<Workspace> {

    ProjectDefaultTagService projectDefaultTagService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Workspace workspace, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        if (operation == LifeCycleHookBinding.Operation.UPDATE
                && changeSpec.map(change -> !"project".equals(change.getFieldName())).orElse(true)) {
            return;
        }
        Project project = workspace.getProject();
        if (project == null) {
            return;
        }
        try {
            projectDefaultTagService.copyDefaults(workspace);
        } catch (RuntimeException e) {
            log.error("Failed to copy the default tags of project {} onto workspace {}", project.getName(),
                    workspace.getName(), e);
        }
    }
}
