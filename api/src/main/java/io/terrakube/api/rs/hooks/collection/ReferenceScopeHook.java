package io.terrakube.api.rs.hooks.collection;

import java.util.Optional;
import java.util.UUID;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.exceptions.HttpStatusException;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;
import lombok.AllArgsConstructor;

/**
 * A reference must name exactly the target its scope implies: a workspace, a project, or neither for the whole
 * organization. A project must belong to the collection's organization. Rejected as 400 before anything is written:
 * the hook runs before the flush, on create and on update, so no later change can break the rule either.
 * Attaching the same collection to the same project, or to the organization, twice is refused as 409: the second
 * attachment would add nothing, and detaching once would leave the collection applied. This is checked here and not
 * with a unique index because the columns are nullable and SQL Server counts NULLs as equal in a unique index.
 */
@AllArgsConstructor
public class ReferenceScopeHook implements LifeCycleHook<Reference> {

    private final ReferenceRepository referenceRepository;

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
                if (referenceRepository.existsByCollectionAndProjectAndIdNot(reference.getCollection(),
                        reference.getProject(), other(reference))) {
                    throw already("project");
                }
            }
            case ORGANIZATION -> {
                if (hasWorkspace || hasProject) {
                    throw new BadRequestException("An organization reference takes neither a workspace nor a project");
                }
                if (reference.getCollection() != null && referenceRepository.existsByCollectionAndScopeAndIdNot(
                        reference.getCollection(), ReferenceScope.ORGANIZATION, other(reference))) {
                    throw already("organization");
                }
            }
        }
    }

    // The id the reference was given, so the check does not find the reference being created.
    private static UUID other(Reference reference) {
        return reference.getId() == null ? UUID.randomUUID() : reference.getId();
    }

    private static HttpStatusException already(String target) {
        return new AlreadyAttachedException("The collection is already attached to this " + target);
    }

    private static final class AlreadyAttachedException extends HttpStatusException {
        AlreadyAttachedException(String message) {
            super(409, message);
        }
    }
}
