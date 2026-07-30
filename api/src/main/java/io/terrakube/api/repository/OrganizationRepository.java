package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
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
}
