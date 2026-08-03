package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Workspace getByOrganizationNameAndName(String organizationName, String workspaceName);

    // The "(organization_id, name)" unique constraint is DEFERRABLE INITIALLY DEFERRED, so
    // Hibernate's auto-flush-before-query can momentarily land two rows with the same name
    // in the table (the pre-existing one plus the pending insert being validated) before
    // Postgres enforces uniqueness at commit. A single-result derived method would throw
    // IncorrectResultSizeDataAccessException in that window, so callers doing a proactive
    // uniqueness check must use this list form and filter out the entity being validated.
    List<Workspace> findAllByOrganizationNameAndName(String organizationName, String workspaceName);

    Optional<List<Workspace>> findWorkspacesByOrganizationNameAndNameStartingWith(String organizationName, String workspaceNameStartingWidth);

    Optional<List<Workspace>> findWorkspacesByOrganization(Organization organization);

    @Query("SELECT w FROM workspace w JOIN w.webhook wh " +
           "WHERE (LOWER(w.source) = LOWER(:normalizedSource) " +
           "OR LOWER(w.source) = LOWER(CONCAT(:normalizedSource, '.git')) " +
           "OR LOWER(w.source) = LOWER(CONCAT(:normalizedSource, '/')) " +
           "OR LOWER(w.source) = LOWER(CONCAT(:normalizedSource, '.git/'))) " +
           "AND wh.migratedV2 = true")
    List<Workspace> findByNormalizedSourceWithMigratedWebhook(@Param("normalizedSource") String normalizedSource);

}
