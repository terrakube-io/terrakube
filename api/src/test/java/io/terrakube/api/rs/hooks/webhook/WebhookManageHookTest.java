package io.terrakube.api.rs.hooks.webhook;

import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
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
    RepoWebhookRepository repoWebhookRepository;

    @InjectMocks
    WebhookManageHook subject;

    @Mock
    RequestScope requestScope;

    @BeforeEach
    void setUp() {
    }

    @Test
    void execute_migrationV2_removesV1Webhook() {
        // Setup
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

        // Execute
        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, webhook, requestScope, Optional.empty());

        // Verify
        verify(repoWebhookService).getOrCreateRepoWebhook(workspace);
        verify(repoWebhookService).createOrUpdateSharedWebhook(repoWebhook);
        verify(gitHubWebhookService).deleteWebhook(workspace, "v1-hook-id");
        assert(webhook.getRemoteHookId() == null);
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
