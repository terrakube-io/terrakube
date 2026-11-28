package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /**
     * Counts the workspaces a person can still see in a project. Native so it is explicit about {@code deleted}. The
     * flag is compared as an integer because SQL Server has no boolean literals.
     */
    @Query(value = "select count(w.id) from workspace w where w.project_id = :projectId and cast(w.deleted as integer) = 0",
            nativeQuery = true)
    long countLiveByProject(@Param("projectId") String projectId);

    /**
     * Records a drift check on its own columns, so it cannot write back what a concurrent save of the entity changed,
     * and only when no newer check is recorded: a late write of an older run must not replace a newer result.
     *
     * @return 1 when recorded, 0 when a newer check is already there
     */
    @Transactional
    @Modifying
    @Query("update workspace w set w.driftStatus = :status, w.driftFirstSeen = :firstSeen, "
            + "w.driftLastChecked = :checked, w.driftJobId = :jobId where w.id = :id "
            + "and (w.driftJobId is null or w.driftJobId <= :jobId)")
    int updateDrift(@Param("id") UUID id, @Param("status") String status, @Param("firstSeen") Date firstSeen,
                    @Param("checked") Date checked, @Param("jobId") Integer jobId);

    /** A page of the ids of the project's workspaces, without a count query. Soft deleted ones are excluded. */
    @Query("select w.id from workspace w where w.project = :project order by w.id")
    Slice<UUID> findIdsByProject(@Param("project") Project project, Pageable pageable);


    /**
     * Lets go of the soft deleted workspaces of a project. They are invisible, so they cannot be moved or deleted
     * by the person deleting the project, yet their {@code project_id} would still fail {@code fk_workspace_project}.
     */
    @Transactional
    @Modifying
    @Query(value = "update workspace set project_id = null where project_id = :projectId and cast(deleted as integer) = 1",
            nativeQuery = true)
    int detachDeletedFromProject(@Param("projectId") String projectId);


    /**
     * Returns a page of workspaces for drift-scan use; generates a paginated SELECT
     * rather than an unguarded findAll() that loads the entire table into memory.
     */
    Page<Workspace> findAll(Pageable pageable);

    /**
     * Workspaces whose last job status says a run is still active although none of their runs is.
     * That is what a status change made without Elide's hooks leaves behind (#3727): the run
     * finished or was closed, but the workspace list keeps showing "Waiting approval" or "Running".
     */
    @Query("SELECT w FROM workspace w WHERE w.deleted = false AND w.lastJobStatus IN :activeStatuses" +
            " AND NOT EXISTS (SELECT 1 FROM job j WHERE j.workspace = w AND j.status IN :activeStatuses)" +
            " ORDER BY w.id")
    List<Workspace> findWithStaleActiveJobStatus(@Param("activeStatuses") List<JobStatus> activeStatuses,
                                                 Pageable pageable);

    /**
     * Corrects the status of a workspace found by {@link #findWithStaleActiveJobStatus}, only if it is still
     * the stale one and still has no active run. A run created since then has set its own status, which this
     * must not overwrite, and only the two columns are written so nothing else of the workspace is touched.
     *
     * @return 1 when the workspace was corrected, 0 when it changed in the meantime
     */
    @Modifying
    @Query("UPDATE workspace w SET w.lastJobStatus = :newStatus, w.lastJobDate = :newDate" +
            " WHERE w.id = :id AND w.lastJobStatus = :expectedStatus" +
            " AND NOT EXISTS (SELECT 1 FROM job j WHERE j.workspace = w AND j.status IN :activeStatuses)")
    int repairStaleJobStatus(@Param("id") UUID id,
                             @Param("expectedStatus") JobStatus expectedStatus,
                             @Param("newStatus") JobStatus newStatus,
                             @Param("newDate") Date newDate,
                             @Param("activeStatuses") List<JobStatus> activeStatuses);

    Workspace getByOrganizationNameAndName(String organizationName, String workspaceName);

    // Duplicate workspace name checks. The comparison ignores case so that names like "Payments" and
    // "payments" count as the same workspace on every database, not only on case-insensitive collations.
    // They filter on the organization_id column instead of joining the organization by name, and only
    // answer yes or no, so no workspace (or its eager associations) is loaded. Soft-deleted workspaces are
    // left out by the entity's @SQLRestriction. The "(organization_id, name)" unique constraint is
    // DEFERRABLE INITIALLY DEFERRED, and Elide flushes a create or rename before its PRECOMMIT hooks run,
    // so the workspace being validated can already be in the table: callers that have its id must use the
    // IdNot form to leave it out. An exists query can't throw IncorrectResultSizeDataAccessException.
    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name, UUID id);

    Optional<List<Workspace>> findWorkspacesByOrganizationNameAndNameStartingWith(String organizationName, String workspaceNameStartingWidth);

    Optional<List<Workspace>> findWorkspacesByOrganization(Organization organization);

    @Query("SELECT w FROM workspace w JOIN w.webhook wh WHERE wh.migratedV2 = true")
    List<Workspace> findAllWithMigratedWebhook();

    default List<Workspace> findByNormalizedSourceWithMigratedWebhook(String normalizedSource) {
        if (normalizedSource == null) {
            return List.of();
        }
        return findAllWithMigratedWebhook().stream()
                .filter(w -> normalizedSource.equalsIgnoreCase(io.terrakube.api.plugin.vcs.RepoUrlNormalizer.normalize(w.getSource())))
                .toList();
    }

    /**
     * Locks the workspace's row for the rest of the transaction, as a plain SELECT ... FOR
     * UPDATE. Bounded to 5s, matching OrganizationRepository.lockForUpdate - an unbounded wait
     * would hold a connection from the pool indefinitely under contention.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT w FROM workspace w WHERE w.id = :id")
    Optional<Workspace> lockForUpdate(@Param("id") UUID id);

    /**
     * Writes only the three cost-tracking columns via native SQL so callers cannot overwrite unrelated workspace fields,
     * and standard JPA entity saves (with {@code insertable = false, updatable = false}) cannot overwrite cost columns.
     */
    @Modifying
    @Query(value = "UPDATE workspace SET current_monthly_cost = :cost, cost_currency = :currency, cost_updated_at = :updatedAt WHERE id = :id",
            nativeQuery = true)
    int updateCost(@Param("id") String id,
                   @Param("cost") BigDecimal cost,
                   @Param("currency") String currency,
                   @Param("updatedAt") Date updatedAt);
}
