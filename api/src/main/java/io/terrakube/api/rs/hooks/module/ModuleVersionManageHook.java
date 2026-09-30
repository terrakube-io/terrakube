package io.terrakube.api.rs.hooks.module;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.scheduler.module.ModuleLatestVersion;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.module.ModuleVersion;
import lombok.AllArgsConstructor;

import java.util.Optional;

/**
 * Removing, restoring or deleting a version can change which one is the newest served version, so
 * {@code Module.latestVersion} is recalculated after the change commits instead of waiting for the next refresh.
 */
@AllArgsConstructor
public class ModuleVersionManageHook implements LifeCycleHook<ModuleVersion> {

    ModuleRepository moduleRepository;
    ModuleLatestVersion moduleLatestVersion;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase transactionPhase,
            ModuleVersion moduleVersion, RequestScope requestScope, Optional<ChangeSpec> changes) {
        moduleRepository.findById(moduleVersion.getModule().getId()).ifPresent(moduleLatestVersion::recalculate);
    }
}
