package io.terrakube.api.plugin.notification;

import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationTrigger;
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

@ExtendWith(MockitoExtension.class)
class JobNotificationTriggerTest {

    @Mock
    NotificationConfigResolver notificationConfigResolver;
    @Mock
    NotificationPayloadRenderer notificationPayloadRenderer;
    @Mock
    NotificationOutboxRepository notificationOutboxRepository;
    @Mock
    NotificationDispatchService notificationDispatchService;

    @InjectMocks
    JobNotificationTrigger subject;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private NotificationConfiguration configWithTrigger(NotificationChannelType type, JobStatus status) {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setId(UUID.randomUUID());
        configuration.setChannelType(type);
        NotificationTrigger trigger = new NotificationTrigger();
        trigger.setJobStatus(status);
        configuration.setTriggers(List.of(trigger));
        return configuration;
    }

    private Job jobWithStatus(JobStatus status) {
        Job job = new Job();
        job.setId(42);
        job.setStatus(status);
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("acme");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("networking");
        workspace.setOrganization(organization);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        return job;
    }

    @Test
    void enqueue_matchingConfigInsertsExactlyOneOutboxRow() {
        Job job = jobWithStatus(JobStatus.failed);
        NotificationConfiguration matching = configWithTrigger(NotificationChannelType.SLACK, JobStatus.failed);
        NotificationConfiguration nonMatching = configWithTrigger(NotificationChannelType.WEBHOOK, JobStatus.completed);
        when(notificationConfigResolver.resolve(job.getWorkspace())).thenReturn(List.of(matching, nonMatching));
        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");

        List<UUID> ids = subject.enqueue(job);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(notificationOutboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getConfiguration()).isEqualTo(matching);
        assertThat(captor.getValue().getJob()).isEqualTo(job);
        assertThat(ids).containsExactly(captor.getValue().getId());
    }

    @Test
    void enqueue_noMatchingConfigInsertsNothing() {
        Job job = jobWithStatus(JobStatus.running);
        when(notificationConfigResolver.resolve(job.getWorkspace()))
                .thenReturn(List.of(configWithTrigger(NotificationChannelType.SLACK, JobStatus.failed)));

        List<UUID> ids = subject.enqueue(job);

        verify(notificationOutboxRepository, never()).save(any());
        assertThat(ids).isEmpty();
    }

    @Test
    void enqueue_jobWithNoWorkspaceIsSkippedWithoutResolving() {
        Job job = new Job();
        job.setId(99);

        List<UUID> ids = subject.enqueue(job);

        verifyNoInteractions(notificationConfigResolver);
        assertThat(ids).isEmpty();
    }

    @Test
    void enqueue_aFailureResolvingConfigsNeverThrows() {
        Job job = jobWithStatus(JobStatus.failed);
        when(notificationConfigResolver.resolve(job.getWorkspace())).thenThrow(new RuntimeException("db down"));

        List<UUID> ids = subject.enqueue(job);

        verify(notificationOutboxRepository, never()).save(any());
        assertThat(ids).isEmpty();
    }

    @Test
    void notifyStatusChanged_dispatchesEveryEnqueuedOutboxRowImmediately() {
        Job job = jobWithStatus(JobStatus.failed);
        NotificationConfiguration matching = configWithTrigger(NotificationChannelType.SLACK, JobStatus.failed);
        when(notificationConfigResolver.resolve(job.getWorkspace())).thenReturn(List.of(matching));
        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");

        subject.notifyStatusChanged(job);

        ArgumentCaptor<UUID> outboxIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationDispatchService, times(1)).dispatchAsync(outboxIdCaptor.capture());
        assertThat(outboxIdCaptor.getValue()).isNotNull();
    }

    @Test
    void notifyStatusChanged_defersDispatchUntilAfterCommitWhenATransactionIsActive() {
        Job job = jobWithStatus(JobStatus.failed);
        NotificationConfiguration matching = configWithTrigger(NotificationChannelType.SLACK, JobStatus.failed);
        when(notificationConfigResolver.resolve(job.getWorkspace())).thenReturn(List.of(matching));
        when(notificationPayloadRenderer.render(eq(NotificationChannelType.SLACK), any())).thenReturn("{}");

        TransactionSynchronizationManager.initSynchronization();
        subject.notifyStatusChanged(job);

        // The outbox row was enqueued, but dispatch must not race an async thread's connection
        // against this still-open transaction's own uncommitted insert.
        verifyNoInteractions(notificationDispatchService);

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(notificationDispatchService, times(1)).dispatchAsync(any());
    }

    @Test
    void notifyStatusChanged_noMatchingConfigDispatchesNothing() {
        Job job = jobWithStatus(JobStatus.running);
        when(notificationConfigResolver.resolve(job.getWorkspace())).thenReturn(List.of());

        subject.notifyStatusChanged(job);

        verifyNoInteractions(notificationDispatchService);
    }
}
