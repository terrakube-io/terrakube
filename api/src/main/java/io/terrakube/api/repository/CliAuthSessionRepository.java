package io.terrakube.api.repository;

import io.terrakube.api.rs.token.login.CliAuthSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface CliAuthSessionRepository extends JpaRepository<CliAuthSession, UUID> {

    Optional<CliAuthSession> findByAuthCodeHash(String authCodeHash);

    long deleteByExpiresAtBefore(Date cutoff);
}
