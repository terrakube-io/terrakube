package io.terrakube.api.rs.hooks.webhook;

import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.RequestScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookManageHookTest {

    @Mock
    WebhookService webhookService;

    @Mock
    RepoWebhookService repoWebhookService;

    @Mock
    GitHubWebhookService gitHubWebhookService;

    @Mock
    GitLabWebhookService gitLabWebhookService;

    @Mock
    RepoWebhookRepository repoWebhookRepository;

    @InjectMocks
    WebhookManageHook subject;

    @Mock
    RequestScope requestScope;

    @BeforeEach
    void setUp() {
    }

    @Test
    void execute_migrationV2GitHub_precommitCreatesSharedWebhookAndRemovesV1Hook() {
        // On migration to v2 the shared repo webhook is created/updated and
        // the old per-workspace (v1) hook is cleaned up synchronously here.
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhook.setRemoteHookId("v1-hook-id");

        RepoWebhook repoWebhook = new RepoWebhook();
        when(repoWebhookService.getOrCreateRepoWebhook(workspace)).thenReturn(repoWebhook);

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService).getOrCreateRepoWebhook(workspace);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
        verify(gitHubWebhookService).deleteWebhook(workspace, "v1-hook-id");
        assertNull(webhook.getRemoteHookId());
        verify(webhookService, never()).createOrUpdateWorkspaceWebhook(any());
    }

    @Test
    void execute_migrationV2GitHub_precommitWithoutV1HookDoesNotDeleteWebhook() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        RepoWebhook repoWebhook = new RepoWebhook();
        when(repoWebhookService.getOrCreateRepoWebhook(workspace)).thenReturn(repoWebhook);

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
        verify(gitHubWebhookService, never()).deleteWebhook(any(), any());
    }

    @Test
    void execute_migrationV2Gitlab_precommitCreatesSharedWebhookAndRemovesV1Hook() {
        // Mirrors the GitHub shared-webhook migration for GitLab-backed
        // workspaces: create/update the shared repo webhook and drop the
        // old per-workspace GitLab hook.
        Workspace workspace = new Workspace();
        workspace.setSource("https://gitlab.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhook.setRemoteHookId("v1-hook-id");

        RepoWebhook repoWebhook = new RepoWebhook();
        when(repoWebhookService.getOrCreateRepoWebhook(workspace)).thenReturn(repoWebhook);

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService).getOrCreateRepoWebhook(workspace);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
        verify(gitLabWebhookService).deleteWebhook(workspace, "v1-hook-id");
        assertNull(webhook.getRemoteHookId());
        verify(webhookService, never()).createOrUpdateWorkspaceWebhook(any());
    }

    @Test
    void execute_notMigratedV2_precommitCreatesWorkspaceWebhook() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(false);

        subject.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(webhookService).createOrUpdateWorkspaceWebhook(webhook);
        verifyNoInteractions(repoWebhookService);
    }

    @Test
    void execute_delete_migratedV2GitHub_postcommitCleansUpOrphanSharedWebhook() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        RepoWebhook repoWebhook = new RepoWebhook();
        when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                .thenReturn(Optional.of(repoWebhook));

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService).cleanupIfOrphan(repoWebhook);
        verify(webhookService, never()).deleteWorkspaceWebhook(any());
    }

    @Test
    void execute_delete_migratedV2Gitlab_postcommitCleansUpOrphanSharedWebhook() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://gitlab.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        RepoWebhook repoWebhook = new RepoWebhook();
        when(repoWebhookRepository.findByRepositoryUrl("https://gitlab.com/owner/repo"))
                .thenReturn(Optional.of(repoWebhook));

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService).cleanupIfOrphan(repoWebhook);
        verify(webhookService, never()).deleteWorkspaceWebhook(any());
    }

    @Test
    void execute_delete_migratedV2GitHub_noSharedWebhookFound_doesNothing() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        when(repoWebhookRepository.findByRepositoryUrl("https://github.com/owner/repo"))
                .thenReturn(Optional.empty());

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookService, never()).cleanupIfOrphan(any());
        verify(webhookService, never()).deleteWorkspaceWebhook(any());
    }

    @Test
    void execute_delete_notMigratedV2_deletesWorkspaceWebhookDirectly() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(false);

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(webhookService).deleteWorkspaceWebhook(webhook);
        verifyNoInteractions(repoWebhookRepository);
        verifyNoInteractions(repoWebhookService);
    }

    @Test
    void execute_forbiddenWithoutPrWorkflow_reportsWebhookPermissionOnly() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        WebhookEvent event = new WebhookEvent();
        event.setPrWorkflowEnabled(false);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setEvents(List.of(event));

        doThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN))
                .when(webhookService).createOrUpdateWorkspaceWebhook(webhook);

        WebhookManagementException exception = assertThrows(WebhookManagementException.class,
                () -> subject.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty()));

        assertEquals(424, exception.getStatus());
        assertTrue(exception.getMessage().contains("'Webhooks: write' permission"));
        assertTrue(!exception.getMessage().contains("Pull requests"));
    }

    @Test
    void execute_forbiddenWithPrWorkflow_reportsPullRequestPermissionToo() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        WebhookEvent event = new WebhookEvent();
        event.setPrWorkflowEnabled(true);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setEvents(List.of(event));

        doThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN))
                .when(webhookService).createOrUpdateWorkspaceWebhook(webhook);

        WebhookManagementException exception = assertThrows(WebhookManagementException.class,
                () -> subject.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty()));

        assertEquals(424, exception.getStatus());
        assertTrue(exception.getMessage().contains("'Pull requests: write' permission"));
    }
}
