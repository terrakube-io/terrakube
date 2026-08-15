package io.terrakube.api.plugin.notification;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.workspace.Workspace;

@Service
public class NotificationConfigResolver {

    private final NotificationConfigurationRepository notificationConfigurationRepository;

    public NotificationConfigResolver(NotificationConfigurationRepository notificationConfigurationRepository) {
        this.notificationConfigurationRepository = notificationConfigurationRepository;
    }

    // Purely additive: a workspace gets every active org-wide config plus every active config
    // scoped directly to it. No per-channel-type override/suppression - keeping the two scopes
    // simple and independent is the whole point.
    public List<NotificationConfiguration> resolve(Workspace workspace) {
        List<NotificationConfiguration> workspaceConfigs = notificationConfigurationRepository
                .findByWorkspaceIdAndActiveTrue(workspace.getId());
        List<NotificationConfiguration> orgConfigs = notificationConfigurationRepository
                .findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(workspace.getOrganization().getId());
        return Stream.concat(workspaceConfigs.stream(), orgConfigs.stream()).toList();
    }
}
