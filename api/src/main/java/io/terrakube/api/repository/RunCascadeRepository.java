package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunCascadeRepository extends JpaRepository<RunCascade, UUID> {

    /** One cascade per origin job - the coordinator's idempotency key, same shape as RunTriggerEvent's. */
    Optional<RunCascade> findByOriginJob_Id(int originJobId);
}
