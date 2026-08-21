package io.terrakube.api.plugin.notification.controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.plugin.notification.NotificationOutboxTransactions;
import io.terrakube.api.repository.NotificationOutboxRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/notification/v1")
public class NotificationDeliveryController {

    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationOutboxTransactions notificationOutboxTransactions;
    private final NotificationDispatchService notificationDispatchService;

    public NotificationDeliveryController(NotificationOutboxRepository notificationOutboxRepository,
            NotificationOutboxTransactions notificationOutboxTransactions,
            NotificationDispatchService notificationDispatchService) {
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationOutboxTransactions = notificationOutboxTransactions;
        this.notificationDispatchService = notificationDispatchService;
    }

    // Read-only transaction kept open through the mapping below: NotificationDeliveryView.from()
    // reads the lazy job/configuration proxies on each row, which otherwise throw
    // LazyInitializationException the moment the repository call (and its session) has returned.
    @Transactional(readOnly = true)
    @GetMapping("/workspace/{workspaceId}/deliveries")
    @PreAuthorize("@notificationConfigurationAccessService.hasManagePermissionForWorkspace(authentication, #workspaceId)")
    public ResponseEntity<List<NotificationDeliveryView>> recentDeliveries(@PathVariable String workspaceId,
            @RequestParam(defaultValue = "10") int limit) {
        List<NotificationDeliveryView> deliveries = notificationOutboxRepository
                .findByJob_Workspace_IdOrderByCreatedDateDesc(UUID.fromString(workspaceId), PageRequest.of(0, limit))
                .stream()
                .map(NotificationDeliveryView::from)
                .toList();
        return ResponseEntity.ok(deliveries);
    }

    // Re-arms a permanently FAILED delivery (a terminal SSRF-blocked/4xx failure, or one that
    // exhausted MAX_ATTEMPTS) and dispatches it immediately instead of waiting for the next
    // poller tick, so a "Retry" click in the UI gets a near-instant result.
    @PostMapping("/workspace/{workspaceId}/deliveries/{deliveryId}/retry")
    @PreAuthorize("@notificationConfigurationAccessService.hasManagePermissionForWorkspace(authentication, #workspaceId)")
    public ResponseEntity<Void> retryDelivery(@PathVariable String workspaceId, @PathVariable String deliveryId) {
        UUID workspaceUuid = UUID.fromString(workspaceId);
        UUID outboxId = UUID.fromString(deliveryId);
        if (!notificationOutboxRepository.existsByIdAndJob_Workspace_Id(outboxId, workspaceUuid)) {
            return ResponseEntity.notFound().build();
        }
        if (!notificationOutboxTransactions.rearmForRetry(outboxId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            notificationDispatchService.dispatchAsync(outboxId);
        } catch (RejectedExecutionException e) {
            // The dispatch executor's queue is full - the row is already re-armed to PENDING
            // (this submission never claimed it), so the outbox poller will pick it up on its
            // next tick. The retry itself still succeeded; don't surface this as an error.
            log.warn("Notification dispatch executor rejected retry of outbox {}, will be picked up by the poller",
                    outboxId);
        }
        return ResponseEntity.ok().build();
    }
}
