package io.terrakube.api.plugin.policy;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyNotificationServiceTest {

    private NotificationOutboxRepository notificationOutboxRepository;
    private NotificationPayloadRenderer notificationPayloadRenderer;
    private PolicyNotificationService policyNotificationService;

    @BeforeEach
    void setUp() {
        notificationOutboxRepository = Mockito.mock(NotificationOutboxRepository.class);
        notificationPayloadRenderer = Mockito.mock(NotificationPayloadRenderer.class);
        policyNotificationService = new PolicyNotificationService(
                notificationOutboxRepository,
                notificationPayloadRenderer,
                "terrakube.example.com"
        );
    }

    @Test
    void sendsNotificationWhenPolicySetHasNotificationConfiguration() {
        Organization org = new Organization();
        org.setName("test-org");

        Workspace ws = new Workspace();
        ws.setName("test-ws");

        Job job = new Job();
        job.setId(42);
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setCommitId("abc1234");

        NotificationConfiguration config = new NotificationConfiguration();
        config.setId(UUID.randomUUID());
        config.setName("slack-alerts");
        config.setChannelType(NotificationChannelType.SLACK);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setName("cis-benchmark");
        policySet.setNotificationConfiguration(config);

        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any(NotificationContext.class)))
                .thenReturn("{\"text\": \"Policy Violation\"}");

        policyNotificationService.sendPolicyViolationNotification(job, List.of(policySet), "Mandatory policy violations detected");

        ArgumentCaptor<NotificationOutbox> outboxCaptor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(notificationOutboxRepository).save(outboxCaptor.capture());
        NotificationOutbox outbox = outboxCaptor.getValue();
        assertNotNull(outbox.getId());
        assertEquals("{\"text\": \"Policy Violation\"}", outbox.getPayload());
        assertEquals(NotificationOutboxStatus.PENDING, outbox.getStatus());
        assertEquals(job, outbox.getJob());
        assertEquals(config, outbox.getConfiguration());
    }

    @Test
    void ignoresPolicySetsWithoutNotificationConfiguration() {
        Organization org = new Organization();
        org.setName("test-org");
        Workspace ws = new Workspace();
        ws.setName("test-ws");

        Job job = new Job();
        job.setId(43);
        job.setOrganization(org);
        job.setWorkspace(ws);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());
        policySet.setName("no-notification-set");
        policySet.setNotificationConfiguration(null);

        policyNotificationService.sendPolicyViolationNotification(job, List.of(policySet), "Violations detected");

        verify(notificationOutboxRepository, never()).save(any());
    }
}
