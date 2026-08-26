package io.terrakube.api.plugin.notification;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTestServiceTest {

    @Mock
    NotificationPayloadRenderer notificationPayloadRenderer;
    @Mock
    NotificationDeliveryService notificationDeliveryService;

    @InjectMocks
    NotificationTestService subject;

    @Test
    void rendersAndDeliversASyntheticPayloadForOrgScopedConfig() {
        Organization organization = new Organization();
        organization.setName("acme");
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setChannelType(NotificationChannelType.SLACK);
        configuration.setOrganization(organization);

        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{\"blocks\":[]}");

        subject.sendTest(configuration);

        ArgumentCaptor<NotificationContext> captor = ArgumentCaptor.forClass(NotificationContext.class);
        verify(notificationPayloadRenderer).render(eq(NotificationChannelType.SLACK), captor.capture());
        assertThat(captor.getValue().organizationName()).isEqualTo("acme");
        assertThat(captor.getValue().workspaceName()).isEqualTo("(test notification)");
        verify(notificationDeliveryService).deliver(configuration, "{\"blocks\":[]}");
    }

    @Test
    void usesWorkspaceNameWhenConfigurationIsWorkspaceScoped() {
        Organization organization = new Organization();
        organization.setName("acme");
        Workspace workspace = new Workspace();
        workspace.setName("networking");
        workspace.setOrganization(organization);
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setOrganization(organization);
        configuration.setWorkspace(workspace);

        when(notificationPayloadRenderer.render(eq(NotificationChannelType.WEBHOOK), any())).thenReturn("{}");

        subject.sendTest(configuration);

        ArgumentCaptor<NotificationContext> captor = ArgumentCaptor.forClass(NotificationContext.class);
        verify(notificationPayloadRenderer).render(eq(NotificationChannelType.WEBHOOK), captor.capture());
        assertThat(captor.getValue().workspaceName()).isEqualTo("networking");
    }
}
