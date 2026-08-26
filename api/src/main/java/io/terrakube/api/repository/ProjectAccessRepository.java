package io.terrakube.api.repository;

import io.terrakube.api.rs.project.access.ProjectAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectAccessRepository extends JpaRepository<ProjectAccess, UUID> {

    Optional<List<ProjectAccess>> findAllByProjectOrganizationIdAndNameIn(UUID projectOrganizationId, List<String> names);

}
