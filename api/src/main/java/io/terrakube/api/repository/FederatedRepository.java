package io.terrakube.api.repository;

import io.terrakube.api.rs.federated.Federated;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FederatedRepository extends JpaRepository<Federated, UUID> {

    Optional<Federated> findByIssuerUrlAndAudience(String issuerUrl, String audience);
}
