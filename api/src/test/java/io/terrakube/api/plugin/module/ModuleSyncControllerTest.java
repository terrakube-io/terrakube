package io.terrakube.api.plugin.module;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshProperties;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshTransactions;
import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;

class ModuleSyncControllerTest {

    private final UUID moduleId = UUID.randomUUID();
    private ModuleRefreshTransactions transactions;
    private ModuleRefreshProperties properties;
    private ModuleRepository moduleRepository;
    private RepoWebhookSyncScheduler repoWebhookSyncScheduler;
    private ModuleSyncController controller;

    @BeforeEach
    void setUp() {
        transactions = mock(ModuleRefreshTransactions.class);
        properties = new ModuleRefreshProperties();
        moduleRepository = mock(ModuleRepository.class);
        repoWebhookSyncScheduler = mock(RepoWebhookSyncScheduler.class);
        controller = new ModuleSyncController(transactions, properties, moduleRepository, repoWebhookSyncScheduler);

        Module module = new Module();
        module.setSource("https://github.com/acme/network.git");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        module.setVcs(vcs);
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
    }

    @Test
    void syncOnlyMarksTheModuleDueWhenWebhooksAreDisabled() {
        controller.sync("org", moduleId.toString());

        verify(transactions).markDue(moduleId);
        verify(repoWebhookSyncScheduler, never()).scheduleSync(any(), any());
    }

    @Test
    void syncRegistersTheRepositoryWebhookWhenEnabled() {
        properties.getWebhook().setEnabled(true);

        controller.sync("org", moduleId.toString());

        verify(transactions).markDue(moduleId);
        verify(repoWebhookSyncScheduler).scheduleSync("https://github.com/acme/network", null);
    }
}
