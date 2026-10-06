package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Organization getOrganizationByName(String name);

    // The "name" unique constraint is DEFERRABLE INITIALLY DEFERRED, so Hibernate's
    // auto-flush-before-query can momentarily land two rows with the same name in the
    // table (the pre-existing one plus the pending insert being validated) before Postgres
    // enforces uniqueness at commit. A single-result derived method would throw
    // IncorrectResultSizeDataAccessException in that window, so callers doing a proactive
    // uniqueness check must use this list form and filter out the entity being validated.
    List<Organization> findAllByName(String name);

    /**
     * Locks the organization's row for the rest of the transaction. A plain
     * {@code SELECT ... FOR UPDATE} rather than a database-specific advisory lock, since every
     * organization row already exists and this needs to work on every RDBMS Terrakube supports.
     * Bounded to 5s: an unbounded wait would hold a connection from the pool indefinitely under
     * contention, rather than failing the request predictably.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT o FROM organization o WHERE o.id = :id")
    Optional<Organization> lockForUpdate(@Param("id") UUID id);
}
