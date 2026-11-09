package io.terrakube.api.rs.hooks.collection;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;

/**
 * A reference must name exactly the target its scope implies: a workspace, a project, or neither for the whole
 * organization. A project must belong to the collection's organization. Rejected as 400 before anything is saved.
 */
public class ReferenceScopeHook implements LifeCycleHook<Reference> {

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Reference reference, RequestScope requestScope,
            Optional<ChangeSpec> changeSpec) {
        ReferenceScope scope = reference.getScope() == null ? ReferenceScope.WORKSPACE : reference.getScope();
        reference.setScope(scope);
        boolean hasWorkspace = reference.getWorkspace() != null;
        boolean hasProject = reference.getProject() != null;
        switch (scope) {
            case WORKSPACE -> {
                if (!hasWorkspace || hasProject) {
                    throw new BadRequestException("A workspace reference needs a workspace and no project");
                }
            }
            case PROJECT -> {
                if (!hasProject || hasWorkspace) {
                    throw new BadRequestException("A project reference needs a project and no workspace");
                }
                var collectionOrganization = reference.getCollection() == null ? null
                        : reference.getCollection().getOrganization();
                var projectOrganization = reference.getProject().getOrganization();
                if (collectionOrganization == null || projectOrganization == null
                        || !collectionOrganization.getId().equals(projectOrganization.getId())) {
                    throw new BadRequestException("The project and the collection must belong to the same organization");
                }
            }
            case ORGANIZATION -> {
                if (hasWorkspace || hasProject) {
                    throw new BadRequestException("An organization reference takes neither a workspace nor a project");
                }
            }
        }
    }
}
