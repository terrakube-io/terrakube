package io.terrakube.api.repository;

import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkspaceRunTriggerRepository extends JpaRepository<WorkspaceRunTrigger, UUID> {

    /**
     * Enabled triggers fired by a source workspace. Workspaces are soft-deleted, so the
     * database FK cascade never fires and rows survive their endpoints; the explicit
     * deleted = false guard is what keeps a removed destination out of the dispatch path.
     *
     * This runs after every completed apply, so it is backed by the
     * (source_workspace_id, enabled) index and fetches the destination eagerly to avoid a
     * query per edge. The destination's organization comes along for a stronger reason than
     * cost: dispatch happens on a Quartz worker thread with no open session, where reaching
     * it lazily would throw LazyInitializationException.
     */
    @EntityGraph(attributePaths = {"destinationWorkspace", "destinationWorkspace.organization", "template"})
    @Query("SELECT t FROM workspace_run_trigger t WHERE t.sourceWorkspace.id = :sourceId AND t.enabled = true AND t.destinationWorkspace.deleted = false")
    List<WorkspaceRunTrigger> findEnabledBySourceWorkspaceId(@Param("sourceId") UUID sourceId);

    /**
     * Edges of an organization as bare ids, for building the adjacency map used by cycle
     * detection. Returns a projection rather than entities so validating a graph never
     * materializes workspaces.
     *
     * Disabled edges are included on purpose. Validating only the enabled ones would leave a
     * hole: declare A -> B disabled, then B -> A (accepted, since the first does not count),
     * then enable A -> B and the cycle exists without any validation having seen it. Keeping
     * the declared graph acyclic means enabling an edge can never introduce one.
     */
    @Query("SELECT t.id AS id, t.sourceWorkspace.id AS sourceId, t.destinationWorkspace.id AS destinationId "
            + "FROM workspace_run_trigger t WHERE t.organization.id = :organizationId "
            + "AND t.sourceWorkspace.deleted = false AND t.destinationWorkspace.deleted = false")
    List<TriggerEdge> findEdgesByOrganizationId(@Param("organizationId") UUID organizationId);

    /** Projection of a single edge: which trigger, from where, to where. */
    interface TriggerEdge {
        UUID getId();
        UUID getSourceId();
        UUID getDestinationId();
    }

    /** Triggers whose destination is the given workspace, for the UI's inbound list. */
    @EntityGraph(attributePaths = {"sourceWorkspace"})
    @Query("SELECT t FROM workspace_run_trigger t WHERE t.destinationWorkspace.id = :destinationId AND t.sourceWorkspace.deleted = false")
    List<WorkspaceRunTrigger> findByDestinationWorkspaceId(@Param("destinationId") UUID destinationId);

    /** Guards the unique constraint with a friendly error instead of a DB violation. */
    boolean existsBySourceWorkspaceIdAndDestinationWorkspaceId(UUID sourceWorkspaceId, UUID destinationWorkspaceId);
}
