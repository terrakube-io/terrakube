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
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Keeps {@code Module.latestVersion} on the newest version that is still served (not removed). */
@Slf4j
@Component
@AllArgsConstructor
public class ModuleLatestVersion {

    private final ModuleRepository moduleRepository;
    private final ModuleVersionRepository moduleVersionRepository;

    public void recalculate(Module module) {
        recalculate(module, () -> moduleVersionRepository.findAllByModuleId(module.getId()));
    }

    /** Uses {@code versions} as the module's full version list, so a caller that already loaded it skips the query. */
    public void recalculate(Module module, List<ModuleVersion> versions) {
        recalculate(module, () -> versions);
    }

    private void recalculate(Module module, Supplier<List<ModuleVersion>> versions) {
        try {
            String latestVersion = versions.get().stream()
                    .filter(moduleVersion -> moduleVersion.getStatus() != VersionStatus.removed)
                    .map(ModuleVersion::getVersion)
                    .filter(Semver::isValid)
                    .max(Comparator.comparing(Semver::parse))
                    .orElse("Version pending");
            if (Objects.equals(latestVersion, module.getLatestVersion())) {
                return;
            }
            module.setLatestVersion(latestVersion);
            log.info("Latest module {}/{} version {}", module.getOrganization().getName(), module.getName(), latestVersion);
            moduleRepository.save(module);
        } catch (Exception e) {
            // Broad catch is intentional: a bad version string must not stop the refresh job or a version update.
            log.error("Failed to calculate latest module version {}/{}", module.getOrganization().getName(), module.getName());
        }
    }
}
