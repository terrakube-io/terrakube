package io.terrakube.api.plugin.notification.sender;

import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;

public interface NotificationSender {
    NotificationChannelType supports();

    void send(NotificationConfiguration configuration, String payload);
}
