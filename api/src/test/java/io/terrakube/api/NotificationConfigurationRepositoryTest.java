package io.terrakube.api;

import io.terrakube.api.plugin.notification.NotificationConfigResolver;
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
import org.hibernate.Hibernate;
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

    @Autowired
    NotificationConfigResolver notificationConfigResolver;

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

    @Test
    void resolve_initializesTriggersAndTemplatesBeforeReturning() {
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Workspace Completed Alerts");
        configuration.setWorkspace(workspace);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("https://example.com/completed-hook");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        NotificationTrigger trigger = new NotificationTrigger();
        trigger.setConfiguration(configuration);
        trigger.setJobStatus(JobStatus.completed);
        notificationTriggerRepository.saveAndFlush(trigger);

        UUID configurationId = configuration.getId();

        // resolve() opens its own read-only transaction and closes it before returning. The
        // background notification dispatch path reads getTriggers()/getTemplates() well after
        // that - both are LAZY with open-in-view disabled, so resolve() must materialize them
        // itself or those reads throw LazyInitializationException ("no Session").
        List<NotificationConfiguration> resolved = notificationConfigResolver.resolve(workspace);

        NotificationConfiguration resolvedConfig = resolved.stream()
                .filter(c -> c.getId().equals(configurationId))
                .findFirst()
                .orElseThrow();

        assertThat(Hibernate.isInitialized(resolvedConfig.getTriggers())).isTrue();
        assertThat(Hibernate.isInitialized(resolvedConfig.getTemplates())).isTrue();
        assertThat(resolvedConfig.getTriggers())
                .extracting(NotificationTrigger::getJobStatus)
                .containsExactly(JobStatus.completed);
    }
}
