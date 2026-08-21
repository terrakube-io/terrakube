package io.terrakube.api.rs.hooks.webhook;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;

import io.terrakube.api.plugin.scheduler.webhook.RepoWebhookSyncScheduler;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;

@ExtendWith(MockitoExtension.class)
class WebhookEventManageHookTest {

    @Mock
    RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @InjectMocks
    WebhookEventManageHook subject;

    @Test
    void execute_v2GithubEventChangeSchedulesSync() {
        WebhookEvent event = eventFor(VcsType.GITHUB, true, "https://github.com/owner/repo.git");

        subject.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, event, null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://github.com/owner/repo", "11111111-1111-1111-1111-111111111111");
    }

    @Test
    void execute_v2GitlabEventChangeSchedulesSync() {
        WebhookEvent event = eventFor(VcsType.GITLAB, true, "https://gitlab.com/owner/repo.git");

        subject.execute(Operation.DELETE, TransactionPhase.POSTCOMMIT, event, null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://gitlab.com/owner/repo", "11111111-1111-1111-1111-111111111111");
    }

    @Test
    void execute_v2AzureSpMiEventChangeSchedulesSync() {
        WebhookEvent event = eventFor(VcsType.AZURE_SP_MI, true, "https://dev.azure.com/org/proj/_git/repo.git");

        subject.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, event, null, Optional.empty());

        verify(repoWebhookSyncScheduler).scheduleSync(
                "https://dev.azure.com/org/proj/repo", "11111111-1111-1111-1111-111111111111");
    }

    @Test
    void execute_nonV2OrNonSharedEventChangeDoesNotScheduleSync() {
        WebhookEvent v1Event = eventFor(VcsType.GITHUB, false, "https://github.com/owner/repo");
        WebhookEvent bitbucketEvent = eventFor(VcsType.BITBUCKET, true, "https://bitbucket.org/owner/repo");

        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, v1Event, null, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, bitbucketEvent, null, Optional.empty());

        verifyNoInteractions(repoWebhookSyncScheduler);
    }

    private WebhookEvent eventFor(VcsType vcsType, boolean migratedV2, String source) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        workspace.setSource(source);
        Vcs vcs = new Vcs();
        vcs.setVcsType(vcsType);
        workspace.setVcs(vcs);

        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(migratedV2);

        WebhookEvent event = new WebhookEvent();
        event.setWebhook(webhook);
        return event;
    }
}
