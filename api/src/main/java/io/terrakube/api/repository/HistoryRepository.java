package io.terrakube.api.repository;

import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface HistoryRepository extends JpaRepository<History, UUID> {

    Optional<History> findFirstByWorkspaceAndJobReferenceOrderByCreatedDateDesc(Workspace workspace, String jobReference);

    Optional<History> findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(Workspace workspace, Date before);
}
