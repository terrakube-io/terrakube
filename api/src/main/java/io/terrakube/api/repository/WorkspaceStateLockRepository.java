package io.terrakube.api.repository;

import io.terrakube.api.rs.workspace.state.lock.WorkspaceStateLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WorkspaceStateLockRepository extends JpaRepository<WorkspaceStateLock, UUID> {
}
