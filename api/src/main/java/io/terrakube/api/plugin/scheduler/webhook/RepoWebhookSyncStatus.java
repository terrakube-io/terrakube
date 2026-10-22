package io.terrakube.api.plugin.scheduler.webhook;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the result of the last sync of a shared repository webhook on the webhook of every workspace that
 * shares it, so the workspace settings can say why the webhook is not working. The sync runs in the
 * background after the settings were saved, so nothing else would tell the person who saved them.
 *
 * <p>Each write is its own transaction: a failed sync rolls its own transaction back, and the message must
 * outlive that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoWebhookSyncStatus {

    static final int MAX_LENGTH = 1024;

    private final WorkspaceRepository workspaceRepository;
    private final WebhookRepository webhookRepository;

    /** Sets the message on the webhooks sharing the repository, or clears it when {@code error} is null. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStatus(String repositoryUrl, String error) {
        String message = error == null || error.length() <= MAX_LENGTH ? error : error.substring(0, MAX_LENGTH);
        for (Workspace workspace : workspaceRepository.findByNormalizedSourceWithMigratedWebhook(repositoryUrl)) {
            Webhook webhook = workspace.getWebhook();
            if (webhook != null && !Objects.equals(webhook.getSyncError(), message)) {
                webhook.setSyncError(message);
                webhookRepository.save(webhook);
            }
        }
    }

    /** A message a person can act on. It never carries the response of the provider, which can echo secrets. */
    static String describe(Throwable failure) {
        // The provider error can sit under a wrapper (transaction, completion or scheduler exceptions).
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = describeOne(current);
            if (message != null) {
                return message;
            }
        }
        return "The webhook could not be synced. See the Terrakube API log for details.";
    }

    private static String describeOne(Throwable failure) {
        if (failure instanceof HttpStatusCodeException http) {
            int status = http.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                return "The VCS connection does not have permission to create or update the webhook on this repository. "
                        + "Check that it is allowed to manage webhooks.";
            }
            if (status == HttpStatus.NOT_FOUND.value()) {
                return "The webhook could not be created or updated: the repository was not found, or the VCS connection "
                        + "cannot access it. Check the repository URL and the permissions of the VCS connection.";
            }
            return "The VCS provider refused the webhook update (HTTP " + status + ").";
        }
        if (failure instanceof ResourceAccessException) {
            return "The VCS provider could not be reached to update the webhook. It will be tried again on the next change.";
        }
        return null;
    }
}
