package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunCascadeEdgeRepository extends JpaRepository<RunCascadeEdge, UUID> {

    List<RunCascadeEdge> findByCascade_Id(UUID cascadeId);

    /** Outbound edges from one node - who to evaluate next once it succeeds. */
    List<RunCascadeEdge> findByCascade_IdAndSourceWorkspace_Id(UUID cascadeId, UUID sourceWorkspaceId);

    /** Inbound edges to one node - every parent an ALL/ANY node's readiness depends on. */
    List<RunCascadeEdge> findByCascade_IdAndDestinationWorkspace_Id(UUID cascadeId, UUID destinationWorkspaceId);
}
