package io.terrakube.api.plugin.notification.controller;

import io.terrakube.api.rs.notification.NotificationChannelType;

public record NotificationAdHocTestRequest(NotificationChannelType channelType, String destinationUrl,
        String signingSecret) {
}
