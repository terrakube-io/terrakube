package io.terrakube.api.rs.hooks.project;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.PolicyAttachmentRepository;
import io.terrakube.api.repository.PolicyExemptionRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.project.Project;
import lombok.AllArgsConstructor;

/**
 * Refuses to delete a project while anything still points at it: workspaces, variable collections, policy
 * attachments and policy exemptions. {@code fk_workspace_project} has no cascade, so
 * without this the delete fails on the foreign key. Soft deleted workspaces keep their {@code project_id}, so they
 * block the delete too, and the count bypasses the workspace soft delete restriction.
 */
@AllArgsConstructor
public class ProjectDeleteGuardHook implements LifeCycleHook<Project> {

    WorkspaceRepository workspaceRepository;
    ReferenceRepository referenceRepository;
    PolicyAttachmentRepository policyAttachmentRepository;
    PolicyExemptionRepository policyExemptionRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Project project, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        long references = referenceRepository.countByProjectId(project.getId().toString());
        if (references > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has "
                    + count(references, "variable collection") + " attached. Detach them before deleting the project.");
        }
        long attachments = policyAttachmentRepository.countByProject(project);
        long exemptions = policyExemptionRepository.countByProject(project);
        if (attachments + exemptions > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has "
                    + count(attachments, "policy attachment") + " and " + count(exemptions, "policy exemption")
                    + ". Remove them before deleting the project.");
        }
        long workspaces = workspaceRepository.countAllByProjectIncludingDeleted(project.getId().toString());
        if (workspaces > 0) {
            throw new ProjectDeleteGuardException("Project " + project.getName() + " still has "
                    + count(workspaces, "workspace") + ". Move or delete them before deleting the project.");
        }
    }

    private static String count(long number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }
}
