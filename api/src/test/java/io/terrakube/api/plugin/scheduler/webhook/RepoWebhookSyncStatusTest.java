package io.terrakube.api.plugin.scheduler.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.Workspace;

class RepoWebhookSyncStatusTest {

    private static final String URL = "https://github.com/owner/repo";

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final WebhookRepository webhookRepository = mock(WebhookRepository.class);
    private final RepoWebhookSyncStatus subject = new RepoWebhookSyncStatus(workspaceRepository, webhookRepository);

    private Workspace workspaceWithWebhook(String syncError) {
        Webhook webhook = new Webhook();
        webhook.setSyncError(syncError);
        Workspace workspace = new Workspace();
        workspace.setWebhook(webhook);
        return workspace;
    }

    private static HttpClientErrorException http(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                "{\"message\":\"secret-in-body\"}".getBytes(), null);
    }

    @Test
    void findsTheProviderErrorUnderAWrapper() {
        Exception wrapped = new IllegalStateException("outer", new RuntimeException("middle", http(HttpStatus.FORBIDDEN)));
        assertTrue(RepoWebhookSyncStatus.describe(wrapped).contains("permission"));
        assertTrue(RepoWebhookSyncStatus.describe(new RuntimeException(new ResourceAccessException("x"))).contains("could not be reached"));
    }

    @Test
    void recordsTheMessageOnEveryWorkspaceSharingTheRepository() {
        Workspace first = workspaceWithWebhook(null);
        Workspace second = workspaceWithWebhook("old");
        doReturn(List.of(first, second)).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(URL);

        subject.recordStatus(URL, "new");

        assertEquals("new", first.getWebhook().getSyncError());
        assertEquals("new", second.getWebhook().getSyncError());
        verify(webhookRepository).save(first.getWebhook());
        verify(webhookRepository).save(second.getWebhook());
    }

    @Test
    void clearsTheMessageWhenTheSyncWorked() {
        Workspace workspace = workspaceWithWebhook("old");
        doReturn(List.of(workspace)).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(URL);

        subject.recordStatus(URL, null);

        assertNull(workspace.getWebhook().getSyncError());
        verify(webhookRepository).save(workspace.getWebhook());
    }

    @Test
    void doesNotWriteAWebhookThatAlreadyShowsTheSameResult() {
        Workspace workspace = workspaceWithWebhook(null);
        doReturn(List.of(workspace)).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(URL);

        subject.recordStatus(URL, null);

        verify(webhookRepository, never()).save(any());
    }

    @Test
    void keepsTheMessageWithinTheColumn() {
        Workspace workspace = workspaceWithWebhook(null);
        doReturn(List.of(workspace)).when(workspaceRepository).findByNormalizedSourceWithMigratedWebhook(URL);

        subject.recordStatus(URL, "x".repeat(5000));

        assertEquals(RepoWebhookSyncStatus.MAX_LENGTH, workspace.getWebhook().getSyncError().length());
    }

    @Test
    void permissionFailuresSayWhatToCheck() {
        assertTrue(RepoWebhookSyncStatus.describe(http(HttpStatus.FORBIDDEN)).contains("permission"));
        assertTrue(RepoWebhookSyncStatus.describe(http(HttpStatus.UNAUTHORIZED)).contains("permission"));
    }

    @Test
    void aMissingRepositoryIsNotReportedAsAMissingHook() {
        String message = RepoWebhookSyncStatus.describe(http(HttpStatus.NOT_FOUND));

        assertTrue(message.contains("repository was not found"), message);
    }

    @Test
    void otherFailuresNameTheStatusAndNeverTheResponseBody() {
        String message = RepoWebhookSyncStatus.describe(http(HttpStatus.UNPROCESSABLE_ENTITY));

        assertTrue(message.contains("422"), message);
        assertFalse(message.contains("secret-in-body"));
        assertFalse(RepoWebhookSyncStatus.describe(new ResourceAccessException("connect timed out")).contains("timed out"));
        assertTrue(RepoWebhookSyncStatus.describe(new ResourceAccessException("x")).contains("could not be reached"));
        assertFalse(RepoWebhookSyncStatus.describe(new IllegalStateException("password=abc")).contains("password"));
    }
}
