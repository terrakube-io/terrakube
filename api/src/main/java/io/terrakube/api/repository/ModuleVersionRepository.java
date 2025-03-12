package io.terrakube.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.terrakube.api.rs.module.ModuleVersion;

public interface ModuleVersionRepository extends JpaRepository<ModuleVersion, UUID> {
    long deleteByModuleId(UUID moduleId);
    
    List<ModuleVersion> findAllByModuleId(UUID moduleId);
}
