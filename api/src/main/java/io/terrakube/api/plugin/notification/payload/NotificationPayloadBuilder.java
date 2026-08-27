package io.terrakube.api.plugin.notification.payload;

import io.terrakube.api.rs.notification.NotificationChannelType;

public interface NotificationPayloadBuilder {
    NotificationChannelType supports();

    String build(NotificationContext context);
}
