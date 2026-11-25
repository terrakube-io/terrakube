package io.terrakube.api.rs.hooks.project;

import java.util.Optional;
import java.util.UUID;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.rs.project.tag.ProjectTag;
import io.terrakube.api.rs.tag.Tag;
import lombok.AllArgsConstructor;

/**
 * A default tag must name a tag that exists and belongs to the project's own organization. The database only checks
 * that the tag id exists, so without this a project could carry, and copy onto its workspaces, the tag ids of another
 * organization. Runs before the flush on create and update, so nothing is written for a tag that is refused.
 */
@AllArgsConstructor
public class ProjectTagHook implements LifeCycleHook<ProjectTag> {

    private final TagRepository tagRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, ProjectTag projectTag, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        String tagId = projectTag.getTagId();
        if (tagId == null || tagId.isBlank()) {
            throw new BadRequestException("A default tag needs a tag");
        }
        UUID id;
        try {
            id = UUID.fromString(tagId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The tag does not exist in this organization");
        }
        Tag tag = tagRepository.findById(id).orElse(null);
        var project = projectTag.getProject();
        if (tag == null || tag.getOrganization() == null || project == null || project.getOrganization() == null
                || !tag.getOrganization().getId().equals(project.getOrganization().getId())) {
            // The same answer for a tag that does not exist and one that belongs elsewhere, so ids cannot be probed.
            throw new BadRequestException("The tag does not exist in this organization");
        }
    }
}
