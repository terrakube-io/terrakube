package io.terrakube.api.plugin.scheduler.module;

import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.VersionStatus;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ModuleLatestVersionTest {

    private final ModuleRepository moduleRepository = mock(ModuleRepository.class);
    private final ModuleVersionRepository moduleVersionRepository = mock(ModuleVersionRepository.class);
    private final ModuleLatestVersion moduleLatestVersion =
            new ModuleLatestVersion(moduleRepository, moduleVersionRepository);

    @Test
    void savesTheNewestServedVersionFromThePreloadedList() {
        Module module = module("1.0.0");

        moduleLatestVersion.recalculate(module,
                List.of(version("1.0.0", VersionStatus.active), version("1.2.0", VersionStatus.deprecated),
                        version("2.0.0", VersionStatus.removed)));

        assertThat(module.getLatestVersion()).isEqualTo("1.2.0");
        verify(moduleRepository).save(module);
        verifyNoInteractions(moduleVersionRepository);
    }

    @Test
    void skipsTheSaveWhenTheLatestVersionIsUnchanged() {
        Module module = module("1.2.0");

        moduleLatestVersion.recalculate(module,
                List.of(version("1.0.0", VersionStatus.active), version("1.2.0", VersionStatus.active)));

        verify(moduleRepository, never()).save(module);
    }

    @Test
    void marksAModuleWithoutServedVersionsAsPending() {
        Module module = module(null);

        moduleLatestVersion.recalculate(module, List.of(version("1.0.0", VersionStatus.removed)));

        assertThat(module.getLatestVersion()).isEqualTo("Version pending");
        verify(moduleRepository).save(module);
    }

    private static Module module(String latestVersion) {
        Organization organization = new Organization();
        organization.setName("org");
        Module module = new Module();
        module.setName("module");
        module.setOrganization(organization);
        module.setLatestVersion(latestVersion);
        return module;
    }

    private static ModuleVersion version(String version, VersionStatus status) {
        ModuleVersion moduleVersion = new ModuleVersion();
        moduleVersion.setVersion(version);
        moduleVersion.setStatus(status);
        return moduleVersion;
    }
}
