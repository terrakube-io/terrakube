package io.terrakube.api.repository;

import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface CliAuthSessionRepository extends JpaRepository<CliAuthSession, UUID> {

    Optional<CliAuthSession> findByAuthCodeHash(String authCodeHash);

    long deleteByExpiresAtBefore(Date cutoff);

    // Compare-and-set on status; returns 1 only for the caller that won the transition.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CliAuthSession s set s.status = :to where s.id = :id and s.status = :from")
    int transitionStatus(UUID id, CliAuthSessionStatus from, CliAuthSessionStatus to);
}
