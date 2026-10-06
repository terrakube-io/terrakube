package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunCascadeNodeAttemptRepository extends JpaRepository<RunCascadeNodeAttempt, UUID> {

    List<RunCascadeNodeAttempt> findByNode_IdOrderByAttemptNumberAsc(UUID nodeId);

    /** Whether a completed job is itself part of an existing cascade - how a chained completion continues one. */
    Optional<RunCascadeNodeAttempt> findByJob_Id(int jobId);
}
