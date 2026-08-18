package io.terrakube.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.terrakube.api.rs.notification.NotificationConfiguration;

public interface NotificationConfigurationRepository extends JpaRepository<NotificationConfiguration, UUID> {

    List<NotificationConfiguration> findByWorkspaceIdAndActiveTrue(UUID workspaceId);

    List<NotificationConfiguration> findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(UUID organizationId);
}
