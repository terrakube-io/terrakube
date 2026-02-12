package io.terrakube.api.repository;

import io.terrakube.api.rs.provider.implementation.Version;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderVersionRepository extends JpaRepository<Version, UUID> {
    List<Version> findAllByProviderId(UUID providerId);
    long deleteByProviderId(UUID providerId);
}
