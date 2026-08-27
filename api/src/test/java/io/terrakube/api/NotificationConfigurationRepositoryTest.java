package io.terrakube.api;

import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.NotificationTriggerRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationTrigger;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConfigurationRepositoryTest extends ServerApplicationTests {

    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;

    @Autowired
    NotificationTriggerRepository notificationTriggerRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Test
    void savesWorkspaceScopedConfiguration_organizationIsDerivedFromWorkspace() {
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        // Mirrors what Elide's nested-URL relationship inference does when creating via
        // organization/{orgId}/workspace/{wsId}/notificationConfiguration: only the
        // immediate parent (workspace) gets set, organization is left null.
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Workspace Webhook");
        configuration.setWorkspace(workspace);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("https://example.com/hook");
        configuration.setActive(true);

        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        assertThat(configuration.getOrganization()).isEqualTo(workspace.getOrganization());
    }

    @Test
    void savesOrgScopedConfigurationWithTriggers() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Org Slack Alerts");
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.SLACK);
        configuration.setDestinationUrl("https://hooks.slack.com/services/X/Y/Z");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        NotificationTrigger trigger = new NotificationTrigger();
        trigger.setConfiguration(configuration);
        trigger.setJobStatus(JobStatus.failed);
        notificationTriggerRepository.saveAndFlush(trigger);

        List<NotificationConfiguration> found = notificationConfigurationRepository
                .findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(organization.getId());

        assertThat(found).extracting(NotificationConfiguration::getId).contains(configuration.getId());
    }
}
