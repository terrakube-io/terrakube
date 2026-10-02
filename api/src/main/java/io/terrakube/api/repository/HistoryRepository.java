package io.terrakube.api.repository;

import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface HistoryRepository extends JpaRepository<History, UUID> {

    /** Most recent state version for a workspace. Compared against the method below to detect a new write. */
    Optional<History> findFirstByWorkspaceOrderByCreatedDateDesc(Workspace workspace);

    /** The state version current for a workspace immediately before the given instant. */
    Optional<History> findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(
            Workspace workspace, Date before);
}
