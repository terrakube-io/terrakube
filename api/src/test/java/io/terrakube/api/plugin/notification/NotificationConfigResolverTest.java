package io.terrakube.api.plugin.notification;

import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationConfigResolverTest {

    @Mock
    NotificationConfigurationRepository notificationConfigurationRepository;

    @InjectMocks
    NotificationConfigResolver resolver;

    private NotificationConfiguration config(NotificationChannelType type, String name) {
        NotificationConfiguration c = new NotificationConfiguration();
        c.setId(UUID.randomUUID());
        c.setChannelType(type);
        c.setName(name);
        c.setActive(true);
        return c;
    }

    @Test
    void workspaceAndOrgConfigsOfTheSameChannelTypeBothApply() {
        UUID orgId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Organization organization = new Organization();
        organization.setId(orgId);
        workspace.setOrganization(organization);

        NotificationConfiguration workspaceSlack = config(NotificationChannelType.SLACK, "workspace-slack");
        NotificationConfiguration orgSlack = config(NotificationChannelType.SLACK, "org-slack");
        NotificationConfiguration orgTeams = config(NotificationChannelType.TEAMS, "org-teams");

        when(notificationConfigurationRepository.findByWorkspaceIdAndActiveTrue(workspaceId))
                .thenReturn(List.of(workspaceSlack));
        when(notificationConfigurationRepository.findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(orgId))
                .thenReturn(List.of(orgSlack, orgTeams));

        List<NotificationConfiguration> effective = resolver.resolve(workspace);

        // No suppression by channel type: the workspace's own Slack config and the org's Slack
        // default both apply, alongside the org's Teams default.
        assertThat(effective).containsExactlyInAnyOrder(workspaceSlack, orgSlack, orgTeams);
    }

    @Test
    void noWorkspaceConfigs_allOrgConfigsApply() {
        UUID orgId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Organization organization = new Organization();
        organization.setId(orgId);
        workspace.setOrganization(organization);

        NotificationConfiguration orgSlack = config(NotificationChannelType.SLACK, "org-slack");

        when(notificationConfigurationRepository.findByWorkspaceIdAndActiveTrue(workspaceId))
                .thenReturn(List.of());
        when(notificationConfigurationRepository.findByOrganizationIdAndWorkspaceIsNullAndActiveTrue(orgId))
                .thenReturn(List.of(orgSlack));

        assertThat(resolver.resolve(workspace)).containsExactly(orgSlack);
    }
}
