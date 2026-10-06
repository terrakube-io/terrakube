package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunCascadeNodeAttemptRepository extends JpaRepository<RunCascadeNodeAttempt, UUID> {

    List<RunCascadeNodeAttempt> findByNode_IdOrderByAttemptNumberAsc(UUID nodeId);
}
