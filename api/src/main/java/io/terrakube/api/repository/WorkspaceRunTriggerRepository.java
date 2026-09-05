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
     * Enabled triggers fired by a source workspace. This runs on the dispatch path after
     * every completed apply, so it is backed by the (source_workspace_id, enabled) index and
     * fetches the destination eagerly to avoid a query per edge.
     */
    @EntityGraph(attributePaths = {"destinationWorkspace", "template"})
    @Query("SELECT t FROM workspace_run_trigger t WHERE t.sourceWorkspace.id = :sourceId AND t.enabled = true")
    List<WorkspaceRunTrigger> findEnabledBySourceWorkspaceId(@Param("sourceId") UUID sourceId);

    /**
     * Every edge of an organization, used to build the adjacency map for cycle detection.
     * Only ids are needed there, so the workspaces are left lazy on purpose.
     */
    @Query("SELECT t FROM workspace_run_trigger t WHERE t.organization.id = :organizationId AND t.enabled = true")
    List<WorkspaceRunTrigger> findEnabledByOrganizationId(@Param("organizationId") UUID organizationId);

    /** Triggers whose destination is the given workspace, for the UI's inbound list. */
    @EntityGraph(attributePaths = {"sourceWorkspace"})
    @Query("SELECT t FROM workspace_run_trigger t WHERE t.destinationWorkspace.id = :destinationId")
    List<WorkspaceRunTrigger> findByDestinationWorkspaceId(@Param("destinationId") UUID destinationId);

    /** Guards the unique constraint with a friendly error instead of a DB violation. */
    boolean existsBySourceWorkspaceIdAndDestinationWorkspaceId(UUID sourceWorkspaceId, UUID destinationWorkspaceId);
}
