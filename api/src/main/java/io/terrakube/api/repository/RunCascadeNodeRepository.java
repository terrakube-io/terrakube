package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunCascadeNodeRepository extends JpaRepository<RunCascadeNode, UUID> {

    List<RunCascadeNode> findByCascade_Id(UUID cascadeId);

    /** One node per (cascade, workspace) - how a diamond's join resolves to a single row. */
    Optional<RunCascadeNode> findByCascade_IdAndWorkspace_Id(UUID cascadeId, UUID workspaceId);
}
