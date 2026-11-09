package io.terrakube.api.rs.hooks.project;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * Refuses to delete a project while workspaces a person can see still point at it. {@code fk_workspace_project} has
 * no cascade, so without this the delete fails on the foreign key. Soft deleted workspaces are not shown anywhere,
 * so they do not block the delete: they are detached from the project instead.
 */
@AllArgsConstructor
public class ProjectDeleteGuardHook implements LifeCycleHook<Project> {

    WorkspaceRepository workspaceRepository;
    ReferenceRepository referenceRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Project project, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        String projectId = project.getId().toString();
        long references = referenceRepository.countByProjectId(projectId);
        if (references > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has " + references
                    + (references == 1 ? " variable collection" : " variable collections")
                    + " attached. Detach them before deleting the project.");
        }
        long workspaces = workspaceRepository.countLiveByProject(projectId);
        if (workspaces > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has " + workspaces
                    + (workspaces == 1 ? " workspace" : " workspaces")
                    + ". Move or delete them before deleting the project.");
        }
        workspaceRepository.detachDeletedFromProject(projectId);
    }
}
