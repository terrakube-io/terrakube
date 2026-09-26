package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.rs.token.pat.Pat;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface PatRepository extends JpaRepository<Pat, UUID> {

    List<Pat> findByCreatedBy(String createdBy);

    // Single conditional update so a concurrent revoke (deleted=true) is never overwritten
    // by a stale entity save.
    @Transactional
    @Modifying
    @Query("update pat p set p.lastUsedAt = :now where p.id = :id and (p.lastUsedAt is null or p.lastUsedAt < :cutoff)")
    int stampLastUsed(UUID id, Date now, Date cutoff);
}
