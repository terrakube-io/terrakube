package io.terrakube.api.plugin.notification;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationMessageStyle;

@Service
public class NotificationTestService {

    private final NotificationPayloadRenderer notificationPayloadRenderer;
    private final NotificationDeliveryService notificationDeliveryService;

    public NotificationTestService(NotificationPayloadRenderer notificationPayloadRenderer,
            NotificationDeliveryService notificationDeliveryService) {
        this.notificationPayloadRenderer = notificationPayloadRenderer;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    public void sendTest(NotificationConfiguration configuration) {
        String workspaceName = configuration.getWorkspace() != null
                ? configuration.getWorkspace().getName()
                : "(test notification)";
        String configurationName = configuration.getName() != null ? configuration.getName() : "Test Notification";
        NotificationMessageStyle messageStyle = configuration.getMessageStyle() != null
                ? configuration.getMessageStyle() : NotificationMessageStyle.DETAILED;
        // null, not a placeholder like "#" - there's no real run to link to for a test send, and
        // Slack's Block Kit rejects a button whose url isn't a valid absolute URL (invalid_payload).
        NotificationContext context = new NotificationContext(
                configuration.getOrganization().getName(),
                workspaceName,
                0,
                JobStatus.completed,
                null,
                null,
                null,
                configurationName,
                null,
                messageStyle);

        String payload = notificationPayloadRenderer.render(configuration.getChannelType(), context);
        notificationDeliveryService.deliver(configuration, payload);
    }
}
