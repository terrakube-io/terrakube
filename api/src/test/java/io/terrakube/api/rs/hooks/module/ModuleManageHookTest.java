package io.terrakube.api.rs.hooks.module;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshProperties;
import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;

class ModuleManageHookTest {

    private static final String REPO = "https://github.com/acme/network";

    private RepoWebhookSyncScheduler repoWebhookSyncScheduler;
    private ModuleRefreshProperties properties;
    private ModuleManageHook hook;

    @BeforeEach
    void setUp() {
        repoWebhookSyncScheduler = mock(RepoWebhookSyncScheduler.class);
        properties = new ModuleRefreshProperties();
        hook = new ModuleManageHook(mock(Scheduler.class), mock(GitHubTokenService.class), mock(ModuleRepository.class),
                repoWebhookSyncScheduler, properties);
    }

    private static Module module(VcsType vcsType) {
        Module module = new Module();
        module.setName("network");
        module.setProvider("aws");
        module.setSource(REPO + ".git");
        Organization organization = new Organization();
        organization.setName("acme");
        module.setOrganization(organization);
        if (vcsType != null) {
            Vcs vcs = new Vcs();
            vcs.setVcsType(vcsType);
            module.setVcs(vcs);
        }
        return module;
    }

    @Test
    void createDoesNotTouchWebhooksWhenDisabled() {
        hook.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, module(VcsType.GITHUB), null, Optional.empty());

        verify(repoWebhookSyncScheduler, never()).scheduleSync(anyString(), any());
    }

    @Test
    void createSyncsTheRepositoryWebhookWhenEnabled() {
        properties.getWebhook().setEnabled(true);

        hook.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, module(VcsType.GITHUB), null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(REPO, null);
    }

    @Test
    void modulesWithoutASupportedConnectionKeepPolling() {
        properties.getWebhook().setEnabled(true);

        hook.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, module(VcsType.BITBUCKET), null, Optional.empty());
        hook.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, module(null), null, Optional.empty());

        verify(repoWebhookSyncScheduler, never()).scheduleSync(anyString(), any());
    }

    @Test
    void sourceChangeSyncsTheOldAndTheNewRepository() {
        properties.getWebhook().setEnabled(true);
        ChangeSpec change = mock(ChangeSpec.class);
        when(change.getFieldName()).thenReturn("source");
        when(change.getOriginal()).thenReturn("https://github.com/acme/old-network.git");

        hook.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, module(VcsType.GITHUB), null, Optional.of(change));

        verify(repoWebhookSyncScheduler).scheduleSync(REPO, null);
        verify(repoWebhookSyncScheduler).scheduleSync("https://github.com/acme/old-network", null);
    }

    @Test
    void updateDoesNotTouchWebhooksWhenDisabled() {
        ChangeSpec change = mock(ChangeSpec.class);

        hook.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, module(VcsType.GITHUB), null, Optional.of(change));

        verify(repoWebhookSyncScheduler, never()).scheduleSync(anyString(), any());
    }

    @Test
    void deleteSyncsAfterTheCommitWhenEnabled() {
        properties.getWebhook().setEnabled(true);

        hook.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, module(VcsType.GITLAB), null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(REPO, null);
    }
}
