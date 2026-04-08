package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceRepository extends JpaRepository<Reference, UUID> {

    Optional<List<Reference>> findByWorkspace(Workspace workspace);

    boolean existsByWorkspaceAndCollection(Workspace workspace, Collection collection);
}
