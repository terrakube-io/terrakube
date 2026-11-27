package io.terrakube.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    List<WebhookEvent> findByWebhookAndEventOrderByPriorityAsc(Webhook webhook, WebhookEventType event);

    /** A workspace's triggers of one event type, without loading the workspace's webhook first. */
    List<WebhookEvent> findByWebhookWorkspaceIdAndEvent(UUID workspaceId, WebhookEventType event);
}
