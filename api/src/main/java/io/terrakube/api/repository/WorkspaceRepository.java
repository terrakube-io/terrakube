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

    Optional<List<Workspace>> findWorkspacesByOrganizationNameAndNameStartingWith(String organizationName, String workspaceNameStartingWidth);

    Optional<List<Workspace>> findWorkspacesByOrganization(Organization organization);

    @Query("SELECT w FROM workspace w JOIN w.webhook wh " +
           "WHERE (LOWER(w.source) = :normalizedSource " +
           "OR LOWER(w.source) = CONCAT(:normalizedSource, '.git') " +
           "OR LOWER(w.source) = CONCAT(:normalizedSource, '/') " +
           "OR LOWER(w.source) = CONCAT(:normalizedSource, '.git/')) " +
           "AND wh.migratedV2 = true")
    List<Workspace> findByNormalizedSourceWithMigratedWebhook(@Param("normalizedSource") String normalizedSource);

}
