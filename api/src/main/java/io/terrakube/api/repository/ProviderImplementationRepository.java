package io.terrakube.api.repository;

import io.terrakube.api.rs.provider.implementation.Implementation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderImplementationRepository extends JpaRepository<Implementation, UUID> {
    List<Implementation> findAllByVersionId(UUID versionId);
    long deleteByVersionId(UUID versionId);
}
