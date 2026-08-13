package io.terrakube.api.plugin.notification;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationConfiguration;

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
        NotificationContext context = new NotificationContext(
                configuration.getOrganization().getName(),
                workspaceName,
                0,
                JobStatus.completed,
                "#",
                null,
                null);

        String payload = notificationPayloadRenderer.render(configuration.getChannelType(), context);
        notificationDeliveryService.deliver(configuration, payload);
    }
}
