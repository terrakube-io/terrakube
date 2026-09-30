package io.terrakube.api.plugin.scheduler.module;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.VersionStatus;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.semver4j.Semver;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Keeps {@code Module.latestVersion} on the newest version that is still served (not removed). */
@Slf4j
@Component
@AllArgsConstructor
public class ModuleLatestVersion {

    private final ModuleRepository moduleRepository;
    private final ModuleVersionRepository moduleVersionRepository;

    public void recalculate(Module module) {
        try {
            module.setLatestVersion(moduleVersionRepository.findAllByModuleId(module.getId()).stream()
                    .filter(moduleVersion -> moduleVersion.getStatus() != VersionStatus.removed)
                    .map(ModuleVersion::getVersion)
                    .filter(Semver::isValid)
                    .max(Comparator.comparing(Semver::parse))
                    .orElse("Version pending"));
            log.info("Latest module {}/{} version {}", module.getOrganization().getName(), module.getName(), module.getLatestVersion());
            moduleRepository.save(module);
        } catch (Exception e) {
            // Broad catch is intentional: a bad version string must not stop the refresh job or a version update.
            log.error("Failed to calculate latest module version {}/{}", module.getOrganization().getName(), module.getName());
        }
    }
}
