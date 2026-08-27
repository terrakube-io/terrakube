package io.terrakube.api.rs.hooks.webhook;

import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
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
    GitHubWebhookService gitHubWebhookService;

    @Mock
    GitLabWebhookService gitLabWebhookService;

    @Mock
    AzDevOpsWebhookService azDevOpsWebhookService;

    @Mock
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @InjectMocks
    WebhookManageHook subject;

    @Mock
    RequestScope requestScope;

    @BeforeEach
    void setUp() {
    }

    @Test
    void execute_migrationV2_precommitRemovesV1WebhookButDoesNotSyncYet() {
        // The v1-hook cleanup is workspace-specific and stays synchronous in
        // PRECOMMIT; the shared-repo sync must NOT happen here, since the
        // workspace's migrated Webhook row isn't durably committed yet for
        // the (eventually async) job to see.
        Workspace workspace = new Workspace();
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhook.setRemoteHookId("v1-hook-id");

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(gitHubWebhookService).deleteWebhook(workspace, "v1-hook-id");
        assertNull(webhook.getRemoteHookId());
        verifyNoInteractions(repoWebhookSyncScheduler);
    }

    @Test
    void execute_migrationV2_postcommitSchedulesSync() {
        Workspace workspace = new Workspace();
        workspace.setId(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"));
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://github.com/owner/repo", "11111111-1111-1111-1111-111111111111");
    }

    // Covers a real bug found via live UI testing: reverting the last
    // migrated-v2 workspace on a shared repo left the shared RepoWebhook
    // permanently orphaned — both the DB row and the live GitHub webhook —
    // because POSTCOMMIT only scheduled a sync when the entity was *still*
    // migratedV2 after the update. A revert (migratedV2 true -> false) needs
    // the same sync scheduled, so the job can notice this workspace no
    // longer shares the repo and clean up if it was the last one.
    @Test
    void execute_revertFromMigratedV2_postcommitStillSchedulesSync() {
        Workspace workspace = new Workspace();
        workspace.setId(java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"));
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(false);

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://github.com/owner/repo", "33333333-3333-3333-3333-333333333333");
    }

    @Test
    void execute_nonSharedProvider_postcommitDoesNotScheduleSync() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://bitbucket.org/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.BITBUCKET);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(false);

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verifyNoInteractions(repoWebhookSyncScheduler);
    }

    @Test
    void execute_gitlab_postcommitSchedulesSync() {
        Workspace workspace = new Workspace();
        workspace.setId(java.util.UUID.fromString("44444444-4444-4444-4444-444444444444"));
        workspace.setSource("https://gitlab.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://gitlab.com/owner/repo", "44444444-4444-4444-4444-444444444444");
    }

    @Test
    void execute_gitlab_migrationV2_precommitRemovesV1WebhookButDoesNotSyncYet() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://gitlab.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITLAB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhook.setRemoteHookId("gl-v1-hook-id");

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(gitLabWebhookService).deleteWebhook(workspace, "gl-v1-hook-id");
        verifyNoInteractions(gitHubWebhookService);
        assertNull(webhook.getRemoteHookId());
        verifyNoInteractions(repoWebhookSyncScheduler);
    }

    @Test
    void execute_azureSpMi_migrationV2_precommitRemovesV1WebhookButDoesNotSyncYet() {
        Workspace workspace = new Workspace();
        workspace.setSource("https://dev.azure.com/org/proj/_git/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_SP_MI);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhook.setRemoteHookId("sub-v1-hook-id");

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        verify(azDevOpsWebhookService).deleteWebhook(workspace, "sub-v1-hook-id");
        verifyNoInteractions(gitHubWebhookService);
        verifyNoInteractions(gitLabWebhookService);
        assertNull(webhook.getRemoteHookId());
        verifyNoInteractions(repoWebhookSyncScheduler);
    }

    @Test
    void execute_azureSpMi_postcommitSchedulesSync() {
        Workspace workspace = new Workspace();
        workspace.setId(java.util.UUID.fromString("55555555-5555-5555-5555-555555555555"));
        workspace.setSource("https://dev.azure.com/org/proj/_git/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.AZURE_SP_MI);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://dev.azure.com/org/proj/repo", "55555555-5555-5555-5555-555555555555");
    }

    @Test
    void execute_delete_migratedV2_postcommitSchedulesSync() {
        Workspace workspace = new Workspace();
        workspace.setId(java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
        workspace.setSource("https://github.com/owner/repo");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, webhook, requestScope, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://github.com/owner/repo", "22222222-2222-2222-2222-222222222222");
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
        verifyNoInteractions(repoWebhookSyncScheduler);
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
