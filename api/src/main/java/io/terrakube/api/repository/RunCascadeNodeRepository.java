package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeNode;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunCascadeNodeRepository extends JpaRepository<RunCascadeNode, UUID> {

    List<RunCascadeNode> findByCascade_Id(UUID cascadeId);

    /** One node per (cascade, workspace) - how a diamond's join resolves to a single row. */
    Optional<RunCascadeNode> findByCascade_IdAndWorkspace_Id(UUID cascadeId, UUID workspaceId);

    /**
     * Same lookup as {@link #findByCascade_IdAndWorkspace_Id}, but row-locked for the caller's
     * transaction - so two parents of the same {@code ANY}/{@code ALL} join, each dispatching
     * concurrently, serialize on this node instead of both reading {@code PENDING} before
     * either commits {@code RUNNING}. Same pattern as {@code OrganizationRepository#lockForUpdate}
     * / {@code JobRepository#lockForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT n FROM run_cascade_node n WHERE n.cascade.id = :cascadeId AND n.workspace.id = :workspaceId")
    Optional<RunCascadeNode> lockByCascade_IdAndWorkspace_IdForUpdate(
            @Param("cascadeId") UUID cascadeId, @Param("workspaceId") UUID workspaceId);
}
