package io.terrakube.api.plugin.notification.sender;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;

@Service
public class NotificationDeliveryService {

    private final Map<NotificationChannelType, NotificationSender> sendersByType;

    public NotificationDeliveryService(List<NotificationSender> senders) {
        this.sendersByType = senders.stream()
                .collect(Collectors.toMap(NotificationSender::supports, Function.identity()));
    }

    public void deliver(NotificationConfiguration configuration, String payload) {
        NotificationSender sender = sendersByType.get(configuration.getChannelType());
        if (sender == null) {
            throw new NotificationDeliveryException("No sender registered for channel type " + configuration.getChannelType());
        }
        sender.send(configuration, payload);
    }
}
