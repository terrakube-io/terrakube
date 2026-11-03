package io.terrakube.api.plugin.module;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshProperties;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshTransactions;
import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.repository.ModuleRepository;

@RestController
@RequestMapping("/module/v1")
public class ModuleSyncController {

    private final ModuleRefreshTransactions moduleRefreshTransactions;
    private final ModuleRefreshProperties moduleRefreshProperties;
    private final ModuleRepository moduleRepository;
    private final RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    public ModuleSyncController(ModuleRefreshTransactions moduleRefreshTransactions,
            ModuleRefreshProperties moduleRefreshProperties, ModuleRepository moduleRepository,
            RepoWebhookSyncScheduler repoWebhookSyncScheduler) {
        this.moduleRefreshTransactions = moduleRefreshTransactions;
        this.moduleRefreshProperties = moduleRefreshProperties;
        this.moduleRepository = moduleRepository;
        this.repoWebhookSyncScheduler = repoWebhookSyncScheduler;
    }

    // Marks the module due: the poller reads its tags on its next tick (within a minute). Accepted whether
    // or not a refresh is already running; a running one then writes nothing and is redone. With module
    // webhooks enabled it also schedules a sync of the repository webhook.
    @PostMapping("/organization/{orgId}/module/{moduleId}/sync")
    @PreAuthorize("@moduleAccessService.hasManagePermission(authentication, #orgId, #moduleId)")
    public ResponseEntity<Void> sync(@PathVariable String orgId, @PathVariable String moduleId) {
        UUID id = UUID.fromString(moduleId);
        moduleRefreshTransactions.markDue(id);
        // With module webhooks enabled, this also registers the repository webhook of a module created before.
        if (moduleRefreshProperties.getWebhook().isEnabled()) {
            moduleRepository.findById(id)
                    .filter(ModuleRefreshTransactions::canRegisterWebhook)
                    .map(module -> RepoUrlNormalizer.normalize(module.getSource()))
                    .ifPresent(url -> repoWebhookSyncScheduler.scheduleSync(url, null));
        }
        return ResponseEntity.accepted().build();
    }
}
