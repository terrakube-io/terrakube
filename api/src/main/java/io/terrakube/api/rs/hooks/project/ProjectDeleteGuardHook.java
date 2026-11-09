package io.terrakube.api.rs.hooks.project;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * Refuses to delete a project while workspaces still point at it. {@code fk_workspace_project} has no cascade, so
 * without this the delete fails on the foreign key. Soft deleted workspaces keep their {@code project_id}, so they
 * block the delete too, and the count bypasses the workspace soft delete restriction.
 */
@AllArgsConstructor
public class ProjectDeleteGuardHook implements LifeCycleHook<Project> {

    WorkspaceRepository workspaceRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Project project, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        long workspaces = workspaceRepository.countAllByProjectIncludingDeleted(project.getId().toString());
        if (workspaces > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has " + workspaces
                    + (workspaces == 1 ? " workspace" : " workspaces")
                    + ". Move or delete them before deleting the project.");
        }
    }
}
