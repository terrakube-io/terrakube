package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunCascadeRepository extends JpaRepository<RunCascade, UUID> {

    /** One cascade per origin job - the coordinator's idempotency key, same shape as RunTriggerEvent's. */
    Optional<RunCascade> findByOriginJob_Id(int originJobId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT c FROM run_cascade c WHERE c.id = :id")
    Optional<RunCascade> lockForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}
