package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;

/**
 * Orders the variable collections that apply to a workspace. A key defined by more than one collection takes its
 * value from the first one in this order: collections attached to the workspace, then to its project, then to the
 * whole organization, and within each scope the higher priority first.
 */
@Service
@AllArgsConstructor
public class CollectionResolutionService {

    private final ReferenceRepository referenceRepository;

    public List<Collection> resolve(Workspace workspace, Organization organization) {
        List<Collection> ordered = new ArrayList<>();
        ordered.addAll(byPriority(referenceRepository.findByWorkspaceWithCollectionItems(workspace)));
        if (workspace.getProject() != null) {
            ordered.addAll(byPriority(
                    referenceRepository.findByProjectWithCollectionItems(workspace.getProject(), ReferenceScope.PROJECT)));
        }
        ordered.addAll(byPriority(
                referenceRepository.findByOrganizationWithCollectionItems(organization, ReferenceScope.ORGANIZATION)));
        return ordered;
    }

    private static List<Collection> byPriority(List<Reference> references) {
        return references.stream()
                .map(Reference::getCollection)
                .sorted(Comparator.comparing(Collection::getPriority).reversed())
                .toList();
    }
}
