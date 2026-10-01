package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM organization o WHERE o.id = :id")
    Optional<Organization> lockForUpdate(@Param("id") UUID id);
}
