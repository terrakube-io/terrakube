package io.terrakube.api.rs.hooks.project;

import java.util.Optional;
import java.util.UUID;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.TemplateRepository;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * The default template of a project must be a template of the project's own organization. It is copied onto every new
 * workspace of the project, so one that was deleted or belongs elsewhere would make every run of those workspaces fail
 * at start. A blank value means no default. Runs before the flush, on create and update.
 */
@AllArgsConstructor
public class ProjectDefaultTemplateHook implements LifeCycleHook<Project> {

    private final TemplateRepository templateRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Project project, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        String value = project.getDefaultTemplate();
        if (value == null || value.isBlank()) {
            // Whitespace only is the same as none, kept out of the column.
            project.setDefaultTemplate(null);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The default template does not exist in this organization");
        }
        var template = templateRepository.findById(id).orElse(null);
        if (template == null || template.getOrganization() == null || project.getOrganization() == null
                || !template.getOrganization().getId().equals(project.getOrganization().getId())) {
            // The same answer for a template that does not exist and one that belongs elsewhere.
            throw new BadRequestException("The default template does not exist in this organization");
        }
        project.setDefaultTemplate(id.toString());
    }
}
