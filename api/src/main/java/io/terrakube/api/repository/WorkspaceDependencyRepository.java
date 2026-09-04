package io.terrakube.api.repository;

import io.terrakube.api.rs.workspace.dependency.WorkspaceDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WorkspaceDependencyRepository extends JpaRepository<WorkspaceDependency, UUID> {

    /** Workspaces that consume output from the given producer. */
    @Query("SELECT d FROM WorkspaceDependency d WHERE d.dependsOn.id = :producerId")
    List<WorkspaceDependency> findByDependsOnId(@Param("producerId") UUID producerId);

    /** Workspaces the given workspace depends on. */
    @Query("SELECT d FROM WorkspaceDependency d WHERE d.workspace.id = :workspaceId")
    List<WorkspaceDependency> findByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
