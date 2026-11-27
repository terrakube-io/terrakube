package io.terrakube.api.plugin.notification.payload;

import io.terrakube.api.plugin.cost.InfracostSummary;
import io.terrakube.api.rs.notification.NotificationChannelType;

public interface NotificationPayloadBuilder {
    NotificationChannelType supports();

    String build(NotificationContext context);

    default String enrichCost(String payload, InfracostSummary costSummary) {
        return payload;
    }
}
