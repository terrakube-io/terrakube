package io.terrakube.api.repository;

import io.terrakube.api.rs.project.access.ProjectAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectAccessRepository extends JpaRepository<ProjectAccess, UUID> {

    Optional<List<ProjectAccess>> findAllByProjectOrganizationIdAndNameIn(UUID projectOrganizationId, List<String> names);

    @Query("SELECT a FROM ProjectAccess a WHERE a.project.organization.id = :orgId AND LOWER(a.name) IN :names")
    Optional<List<ProjectAccess>> findAllByProjectOrganizationIdAndNameInIgnoreCase(@Param("orgId") UUID orgId, @Param("names") List<String> names);

}
