package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.terrakube.api.rs.workspace.access.Access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessRepository extends JpaRepository<Access, UUID> {

    Optional<List<Access>> findAllByWorkspaceOrganizationIdAndNameIn(UUID workspaceOrganizationId, List<String> names);

    @Query("SELECT a FROM Access a WHERE a.workspace.organization.id = :orgId AND LOWER(a.name) IN :names")
    Optional<List<Access>> findAllByWorkspaceOrganizationIdAndNameInIgnoreCase(@Param("orgId") UUID orgId, @Param("names") List<String> names);

}
