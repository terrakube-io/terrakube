package io.terrakube.api.plugin.project;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.repository.ProjectTagRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.tag.ProjectTag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import lombok.AllArgsConstructor;

/**
 * Copies a project's default tags onto the workspaces that are already in it. Workspaces are read a page at a time,
 * so a project with many workspaces never loads them all. Tags a workspace already has are left alone, including
 * their value, and nothing is ever removed, so applying twice changes nothing the second time.
 */
@Service
@AllArgsConstructor
public class ProjectDefaultTagService {

    static final int PAGE_SIZE = 500;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceTagRepository workspaceTagRepository;
    private final ProjectTagRepository projectTagRepository;

    /** What applying would do (dryRun) or did. */
    public record Result(long workspaces, long workspacesChanged, long tagsAdded) {
    }

    @Transactional
    public Result apply(Project project, boolean dryRun) {
        List<ProjectTag> defaults = projectTagRepository.findByProject(project);
        long workspaces = 0;
        long changed = 0;
        long added = 0;

        int page = 0;
        Page<UUID> ids;
        do {
            ids = workspaceRepository.findIdsByProject(project, PageRequest.of(page++, PAGE_SIZE));
            workspaces += ids.getNumberOfElements();
            if (defaults.isEmpty() || ids.isEmpty()) {
                continue;
            }
            Set<String> existing = new HashSet<>();
            for (Object[] pair : workspaceTagRepository.findExistingPairs(ids.getContent())) {
                existing.add(pair[0] + "|" + pair[1]);
            }
            for (UUID workspaceId : ids.getContent()) {
                boolean touched = false;
                for (ProjectTag projectTag : defaults) {
                    if (existing.contains(workspaceId + "|" + projectTag.getTagId())) {
                        continue;
                    }
                    added++;
                    touched = true;
                    if (!dryRun) {
                        Workspace reference = workspaceRepository.getReferenceById(workspaceId);
                        WorkspaceTag copy = new WorkspaceTag();
                        copy.setWorkspace(reference);
                        copy.setTagId(projectTag.getTagId());
                        copy.setValue(projectTag.getValue());
                        workspaceTagRepository.save(copy);
                    }
                }
                if (touched) {
                    changed++;
                }
            }
        } while (ids.hasNext());
        return new Result(workspaces, changed, added);
    }
}
