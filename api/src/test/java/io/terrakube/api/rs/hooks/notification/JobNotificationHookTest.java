package io.terrakube.api.rs.hooks.notification;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobNotificationHookTest {

    @Mock
    JobNotificationTrigger jobNotificationTrigger;
    @Mock
    NotificationDispatchService notificationDispatchService;
    @Mock
    RequestScope requestScope;

    @InjectMocks
    JobNotificationHook subject;

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
    void precommit_delegatesEnqueueingToJobNotificationTrigger() {
        Job job = jobWithStatus(JobStatus.failed);
        when(jobNotificationTrigger.enqueue(job)).thenReturn(List.of(UUID.randomUUID()));

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, requestScope, Optional.empty());

        verify(jobNotificationTrigger).enqueue(job);
        verifyNoInteractions(notificationDispatchService);
    }

    @Test
    void precommit_ignoredForNonUpdateOperations() {
        Job job = jobWithStatus(JobStatus.failed);

        subject.execute(Operation.CREATE, TransactionPhase.PRECOMMIT, job, requestScope, Optional.empty());

        verifyNoInteractions(jobNotificationTrigger);
    }

    @Test
    void postcommit_dispatchesEveryOutboxIdEnqueuedInThePrecedingPrecommitOnTheSameThread() {
        Job job = jobWithStatus(JobStatus.failed);
        UUID outboxId = UUID.randomUUID();
        when(jobNotificationTrigger.enqueue(job)).thenReturn(List.of(outboxId));

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, requestScope, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, job, requestScope, Optional.empty());

        verify(notificationDispatchService, times(1)).dispatchAsync(outboxId);
    }

    @Test
    void postcommit_dispatchesTheCorrectJobsOutboxIdsWhenTwoJobsUpdateOnTheSameThread() {
        Job jobA = jobWithStatus(JobStatus.failed);
        Job jobB = jobWithStatus(JobStatus.completed);
        jobB.setId(43);
        UUID outboxIdA = UUID.randomUUID();
        UUID outboxIdB = UUID.randomUUID();
        when(jobNotificationTrigger.enqueue(jobA)).thenReturn(List.of(outboxIdA));
        when(jobNotificationTrigger.enqueue(jobB)).thenReturn(List.of(outboxIdB));

        // A single Elide transaction updating two Job entities fires PRECOMMIT for both before
        // either POSTCOMMIT runs - jobB's PRECOMMIT must not clobber jobA's still-pending outbox
        // ids.
        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, jobA, requestScope, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, jobB, requestScope, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, jobA, requestScope, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, jobB, requestScope, Optional.empty());

        verify(notificationDispatchService, times(1)).dispatchAsync(outboxIdA);
        verify(notificationDispatchService, times(1)).dispatchAsync(outboxIdB);
    }

    @Test
    void postcommit_dispatchesNothingWhenPrecommitEnqueuedNothing() {
        Job job = jobWithStatus(JobStatus.running);
        when(jobNotificationTrigger.enqueue(job)).thenReturn(List.of());

        subject.execute(Operation.UPDATE, TransactionPhase.PRECOMMIT, job, requestScope, Optional.empty());
        subject.execute(Operation.UPDATE, TransactionPhase.POSTCOMMIT, job, requestScope, Optional.empty());

        verifyNoInteractions(notificationDispatchService);
    }
}
