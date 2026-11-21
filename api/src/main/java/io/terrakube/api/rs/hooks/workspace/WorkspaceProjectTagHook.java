package io.terrakube.api.rs.hooks.workspace;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.ProjectTagRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.tag.ProjectTag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
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

    ProjectTagRepository projectTagRepository;
    WorkspaceTagRepository workspaceTagRepository;

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
            copyDefaultTags(workspace, project);
        } catch (RuntimeException e) {
            log.error("Failed to copy the default tags of project {} onto workspace {}", project.getName(),
                    workspace.getName(), e);
        }
    }

    private void copyDefaultTags(Workspace workspace, Project project) {
        List<ProjectTag> defaults = projectTagRepository.findByProject(project);
        if (defaults.isEmpty()) {
            return;
        }
        Set<String> existing = workspaceTagRepository.findByWorkspace(workspace).stream()
                .map(WorkspaceTag::getTagId)
                .collect(Collectors.toSet());
        for (ProjectTag projectTag : defaults) {
            if (existing.contains(projectTag.getTagId())) {
                continue;
            }
            WorkspaceTag copy = new WorkspaceTag();
            copy.setWorkspace(workspace);
            copy.setTagId(projectTag.getTagId());
            copy.setValue(projectTag.getValue());
            workspaceTagRepository.save(copy);
            log.info("Copied tag {} from project {} onto workspace {}", projectTag.getTagId(), project.getName(),
                    workspace.getName());
        }
    }
}
