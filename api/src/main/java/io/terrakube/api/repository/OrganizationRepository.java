package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.plugin.organization.OrganizationStatusCountRow;
import io.terrakube.api.plugin.organization.OrganizationSummaryRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Organization getOrganizationByName(String name);

    // The "name" unique constraint is DEFERRABLE INITIALLY DEFERRED, so Hibernate's
    // auto-flush-before-query can momentarily land two rows with the same name in the
    // table (the pre-existing one plus the pending insert being validated) before Postgres
    // enforces uniqueness at commit. A single-result derived method would throw
    // IncorrectResultSizeDataAccessException in that window, so callers doing a proactive
    // uniqueness check must use this list form and filter out the entity being validated.
    List<Organization> findAllByName(String name);

    @Query("""
            SELECT new io.terrakube.api.plugin.organization.OrganizationSummaryRow(
                    o.id, o.name, o.description, o.executionMode, o.icon, COUNT(w.id))
            FROM organization o
            LEFT JOIN workspace w ON w.organization = o AND w.deleted = false
            WHERE o.disabled = false
            GROUP BY o.id, o.name, o.description, o.executionMode, o.icon
            ORDER BY o.name
            """)
    List<OrganizationSummaryRow> findAllSummaryRows();

    /**
     * Organization and workspace visibility mirrors UserBelongsOrganization and Workspace's
     * @ReadPermission checks. It deliberately joins no to-many object graph: EXISTS clauses are
     * evaluated by the database and COUNT operates only on workspaces visible to the caller.
     */
    @Query("""
            SELECT new io.terrakube.api.plugin.organization.OrganizationSummaryRow(
                    o.id, o.name, o.description, o.executionMode, o.icon, COUNT(w.id))
            FROM organization o
            LEFT JOIN workspace w ON w.organization = o AND w.deleted = false AND (
                (w.project IS NULL AND EXISTS (
                    SELECT t1 FROM team t1
                    WHERE t1.organization = o AND t1.name IN :groups
                )) OR
                (w.project IS NOT NULL AND EXISTS (
                    SELECT t2 FROM team t2
                    WHERE t2.organization = o AND t2.name IN :groups AND (
                        LOWER(TRIM(COALESCE(t2.role, 'custom'))) IN ('admin', 'write') OR
                        (LOWER(TRIM(COALESCE(t2.role, 'custom'))) IN ('', 'custom') AND t2.manageWorkspace = true)
                    )
                )) OR
                EXISTS (
                    SELECT a1 FROM access a1
                    WHERE a1.workspace = w AND a1.name IN :groups AND (
                        LOWER(TRIM(COALESCE(a1.role, 'custom'))) IN ('admin', 'write') OR
                        (LOWER(TRIM(COALESCE(a1.role, 'custom'))) IN ('', 'custom') AND a1.manageWorkspace = true)
                    )
                ) OR
                EXISTS (
                    SELECT pa1 FROM project_access pa1
                    WHERE pa1.project = w.project AND pa1.name IN :groups
                )
            )
            WHERE o.disabled = false AND (
                EXISTS (SELECT t0 FROM team t0 WHERE t0.organization = o AND t0.name IN :groups) OR
                EXISTS (SELECT a0 FROM access a0 WHERE a0.workspace.organization = o AND a0.name IN :groups) OR
                EXISTS (SELECT pa0 FROM project_access pa0
                        WHERE pa0.project.organization = o AND pa0.name IN :groups)
            )
            GROUP BY o.id, o.name, o.description, o.executionMode, o.icon
            ORDER BY o.name
            """)
    List<OrganizationSummaryRow> findVisibleSummaryRows(@Param("groups") List<String> groups);

    @Query("""
            SELECT new io.terrakube.api.plugin.organization.OrganizationStatusCountRow(
                    w.organization.id, w.lastJobStatus, COUNT(w.id))
            FROM workspace w
            WHERE w.deleted = false AND w.organization.id IN :organizationIds
            GROUP BY w.organization.id, w.lastJobStatus
            """)
    List<OrganizationStatusCountRow> findStatusCounts(@Param("organizationIds") List<UUID> organizationIds);

    @Query("""
            SELECT new io.terrakube.api.plugin.organization.OrganizationStatusCountRow(
                    w.organization.id, w.lastJobStatus, COUNT(w.id))
            FROM workspace w
            WHERE w.deleted = false AND w.organization.id IN :organizationIds AND (
                (w.project IS NULL AND EXISTS (
                    SELECT t1 FROM team t1
                    WHERE t1.organization = w.organization AND t1.name IN :groups
                )) OR
                (w.project IS NOT NULL AND EXISTS (
                    SELECT t2 FROM team t2
                    WHERE t2.organization = w.organization AND t2.name IN :groups AND (
                        LOWER(TRIM(COALESCE(t2.role, 'custom'))) IN ('admin', 'write') OR
                        (LOWER(TRIM(COALESCE(t2.role, 'custom'))) IN ('', 'custom') AND t2.manageWorkspace = true)
                    )
                )) OR
                EXISTS (
                    SELECT a1 FROM access a1
                    WHERE a1.workspace = w AND a1.name IN :groups AND (
                        LOWER(TRIM(COALESCE(a1.role, 'custom'))) IN ('admin', 'write') OR
                        (LOWER(TRIM(COALESCE(a1.role, 'custom'))) IN ('', 'custom') AND a1.manageWorkspace = true)
                    )
                ) OR
                EXISTS (
                    SELECT pa1 FROM project_access pa1
                    WHERE pa1.project = w.project AND pa1.name IN :groups
                )
            )
            GROUP BY w.organization.id, w.lastJobStatus
            """)
    List<OrganizationStatusCountRow> findVisibleStatusCounts(
            @Param("organizationIds") List<UUID> organizationIds, @Param("groups") List<String> groups);
}
