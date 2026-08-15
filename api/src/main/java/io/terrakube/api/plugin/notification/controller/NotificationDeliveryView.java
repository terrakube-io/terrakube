package io.terrakube.api.plugin.notification.controller;

import java.util.Date;

import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;

public record NotificationDeliveryView(
        String id,
        int jobId,
        String configurationName,
        NotificationChannelType channelType,
        NotificationOutboxStatus status,
        int attemptCount,
        Date lastAttemptAt,
        String lastError,
        Date createdDate) {

    public static NotificationDeliveryView from(NotificationOutbox outbox) {
        return new NotificationDeliveryView(
                outbox.getId().toString(),
                outbox.getJob().getId(),
                outbox.getConfiguration().getName(),
                outbox.getConfiguration().getChannelType(),
                outbox.getStatus(),
                outbox.getAttemptCount(),
                outbox.getLastAttemptAt(),
                outbox.getLastError(),
                outbox.getCreatedDate());
    }
}
