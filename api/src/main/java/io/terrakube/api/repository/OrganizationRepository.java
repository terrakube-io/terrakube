package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Organization getOrganizationByName(String name);

    // Duplicate organization name checks. The comparison ignores case so that names like "Acme" and "acme"
    // count as the same organization on every database, not only on case-insensitive collations. They only
    // answer yes or no, so no organization is loaded. The "name" unique constraint is DEFERRABLE INITIALLY
    // DEFERRED, and Elide flushes a create or rename before its PRECOMMIT hooks run, so the organization
    // being validated can already be in the table: callers that have its id must use the IdNot form to
    // leave it out. An exists query can't throw IncorrectResultSizeDataAccessException.
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /**
     * Locks the organization's row for the rest of the transaction. A plain
     * {@code SELECT ... FOR UPDATE} rather than a database-specific advisory lock, for
     * portability. Bounded to 5s so contention fails the request instead of holding a pool
     * connection indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT o FROM organization o WHERE o.id = :id")
    Optional<Organization> lockForUpdate(@Param("id") UUID id);
}
