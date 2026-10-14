package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.NotificationConfigResolver;
import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationTrigger;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Same test shape as {@code JobNotificationTriggerTest}, keyed on cascade outcome instead. */
@ExtendWith(MockitoExtension.class)
class RunCascadeNotificationTriggerTest {

    @Mock
    NotificationConfigResolver notificationConfigResolver;
    @Mock
    NotificationPayloadRenderer notificationPayloadRenderer;
    @Mock
    NotificationOutboxRepository notificationOutboxRepository;
    @Mock
    NotificationDispatchService notificationDispatchService;
    @Mock
    RunCascadeNodeRepository runCascadeNodeRepository;

    @InjectMocks
    RunCascadeNotificationTrigger subject;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private NotificationConfiguration configWithTrigger(NotificationChannelType type, RunCascadeStatus status) {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setId(UUID.randomUUID());
        configuration.setChannelType(type);
        NotificationTrigger trigger = new NotificationTrigger();
        trigger.setCascadeStatus(status);
        configuration.setTriggers(List.of(trigger));
        return configuration;
    }

    private RunCascade cascadeWithStatus(RunCascadeStatus status) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("acme");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("networking");
        workspace.setOrganization(organization);

        Job originJob = new Job();
        originJob.setId(42);
        originJob.setOrganization(organization);
        originJob.setWorkspace(workspace);

        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(originJob);
        cascade.setStatus(status);
        return cascade;
    }

    private RunCascadeNode nodeWithStatus(RunCascadeNodeStatus status) {
        RunCascadeNode node = new RunCascadeNode();
        node.setStatus(status);
        return node;
    }

    @Test
    void notifyCascadeStatusChanged_matchingConfigInsertsExactlyOneOutboxRowAndDispatchesIt() {
        RunCascade cascade = cascadeWithStatus(RunCascadeStatus.DEGRADED);
        NotificationConfiguration matching = configWithTrigger(NotificationChannelType.SLACK, RunCascadeStatus.DEGRADED);
        NotificationConfiguration nonMatching = configWithTrigger(NotificationChannelType.WEBHOOK, RunCascadeStatus.COMPLETED);
        when(notificationConfigResolver.resolve(cascade.getOriginJob().getWorkspace()))
                .thenReturn(List.of(matching, nonMatching));
        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");
        when(runCascadeNodeRepository.findByCascade_Id(cascade.getId())).thenReturn(List.of(
                nodeWithStatus(RunCascadeNodeStatus.SUCCEEDED), nodeWithStatus(RunCascadeNodeStatus.FAILED)));

        subject.notifyCascadeStatusChanged(cascade);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(notificationOutboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getConfiguration()).isEqualTo(matching);
        assertThat(captor.getValue().getJob()).isEqualTo(cascade.getOriginJob());
        // Null on purpose - see RunCascadeNotificationTrigger.saveOutboxRow.
        assertThat(captor.getValue().getJobStatus()).isNull();
        verify(notificationDispatchService, times(1)).dispatchAsync(captor.getValue().getId());
    }

    @Test
    void notifyCascadeStatusChanged_noMatchingTriggerInsertsNothing() {
        RunCascade cascade = cascadeWithStatus(RunCascadeStatus.COMPLETED);
        when(notificationConfigResolver.resolve(cascade.getOriginJob().getWorkspace()))
                .thenReturn(List.of(configWithTrigger(NotificationChannelType.SLACK, RunCascadeStatus.DEGRADED)));

        subject.notifyCascadeStatusChanged(cascade);

        verify(notificationOutboxRepository, never()).save(any());
        verifyNoInteractions(notificationDispatchService);
    }

    @Test
    void notifyCascadeStatusChanged_cascadeWithNoOriginWorkspaceIsSkippedWithoutResolving() {
        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(new Job());
        cascade.setStatus(RunCascadeStatus.COMPLETED);

        subject.notifyCascadeStatusChanged(cascade);

        verifyNoInteractions(notificationConfigResolver);
    }

    @Test
    void notifyCascadeStatusChanged_aFailureResolvingConfigsNeverThrows() {
        RunCascade cascade = cascadeWithStatus(RunCascadeStatus.COMPLETED);
        when(notificationConfigResolver.resolve(cascade.getOriginJob().getWorkspace()))
                .thenThrow(new RuntimeException("db down"));

        subject.notifyCascadeStatusChanged(cascade);

        verify(notificationOutboxRepository, never()).save(any());
        verifyNoInteractions(notificationDispatchService);
    }

    @Test
    void notifyCascadeStatusChanged_defersDispatchUntilAfterCommitWhenATransactionIsActive() {
        RunCascade cascade = cascadeWithStatus(RunCascadeStatus.COMPLETED);
        NotificationConfiguration matching = configWithTrigger(NotificationChannelType.SLACK, RunCascadeStatus.COMPLETED);
        when(notificationConfigResolver.resolve(cascade.getOriginJob().getWorkspace())).thenReturn(List.of(matching));
        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");
        when(runCascadeNodeRepository.findByCascade_Id(cascade.getId())).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        subject.notifyCascadeStatusChanged(cascade);

        verifyNoInteractions(notificationDispatchService);

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(notificationDispatchService, times(1)).dispatchAsync(any());
    }

    @Test
    void notifyCascadeStatusChanged_configScopedToADifferentTemplateIsSkipped() {
        RunCascade cascade = cascadeWithStatus(RunCascadeStatus.COMPLETED);
        cascade.getOriginJob().setTemplateReference(UUID.randomUUID().toString());
        NotificationConfiguration configuration = configWithTrigger(NotificationChannelType.SLACK, RunCascadeStatus.COMPLETED);
        Template otherTemplate = new Template();
        otherTemplate.setId(UUID.randomUUID());
        configuration.setTemplates(List.of(otherTemplate));
        when(notificationConfigResolver.resolve(cascade.getOriginJob().getWorkspace())).thenReturn(List.of(configuration));

        subject.notifyCascadeStatusChanged(cascade);

        verify(notificationOutboxRepository, never()).save(any());
    }
}
