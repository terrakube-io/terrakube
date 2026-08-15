package io.terrakube.api.plugin.notification.payload;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.terrakube.api.rs.notification.NotificationChannelType;

@Service
public class NotificationPayloadRenderer {

    private final Map<NotificationChannelType, NotificationPayloadBuilder> buildersByType;

    public NotificationPayloadRenderer(List<NotificationPayloadBuilder> builders) {
        this.buildersByType = builders.stream()
                .collect(Collectors.toMap(NotificationPayloadBuilder::supports, Function.identity()));
    }

    public String render(NotificationChannelType channelType, NotificationContext context) {
        NotificationPayloadBuilder builder = buildersByType.get(channelType);
        if (builder == null) {
            throw new IllegalStateException("No payload builder registered for channel type " + channelType);
        }
        return builder.build(context);
    }
}
