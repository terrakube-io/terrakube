package io.terrakube.api.plugin.notification;

import java.util.List;
import java.util.stream.Stream;

import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    //
    // Transactional + explicit initialization: callers (JobNotificationTrigger, invoked from the
    // Quartz/background dispatch path) read configuration.getTriggers() and getTemplates() long
    // after this returns. Both are LAZY and spring.jpa.open-in-view=false, so without materializing
    // them here every access throws LazyInitializationException ("no Session"). Only the two
    // collections notification resolution actually needs are initialized - the relationships stay
    // LAZY globally.
    @Transactional(readOnly = true)
    public List<NotificationConfiguration> resolve(Workspace workspace) {
        List<NotificationConfiguration> workspaceConfigs = notificationConfigurationRepository
                .findByWorkspaceIdAndActiveTrue(workspace.getId());
        List<NotificationConfiguration> orgConfigs = notificationConfigurationRepository
                .findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(workspace.getOrganization().getId());

        List<NotificationConfiguration> configurations = Stream.concat(
                workspaceConfigs.stream(), orgConfigs.stream()).toList();

        configurations.forEach(configuration -> {
            Hibernate.initialize(configuration.getTriggers());
            Hibernate.initialize(configuration.getTemplates());
        });

        return configurations;
    }
}
