package io.terrakube.api.plugin.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.repository.NotificationOutboxRepository;

import lombok.extern.slf4j.Slf4j;

// Shared by two very different callers:
//  - JobNotificationHook (an Elide LifeCycleHook) uses enqueue() only, deferring dispatch to its
//    own POSTCOMMIT phase so a notification is never sent for a Job update that later rolls back.
//  - ScheduleJob/ScheduleJobTrigger use notifyStatusChanged() directly: real job status
//    transitions during a run (queue -> pending -> running -> completed/failed/...) happen via a
//    plain jobRepository.save() on a Quartz-invoked bean, never through an Elide JSON:API/GraphQL
//    request - so JobNotificationHook, which only fires on operations Elide itself observes, never
//    sees them. Calling this directly, immediately after the save that changed the status, is the
//    only way these transitions ever get noticed.
//
// notifyStatusChanged() is safe to call from either a non-transactional caller (ScheduleJob,
// RemoteTfeService - a plain save() is its own auto-committing transaction, already committed by
// the time control returns here) or a transactional one (ScheduleJobTrigger wraps its whole
// execute() in @Transactional): when a transaction is active, the outbox row insert isn't visible
// to the async dispatch thread's own connection until it commits, so dispatch is deferred to run
// after that commit instead of racing it.
@Slf4j
@Service
public class JobNotificationTrigger {

    private final NotificationConfigResolver notificationConfigResolver;
    private final NotificationPayloadRenderer notificationPayloadRenderer;
    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationDispatchService notificationDispatchService;

    @Value("${io.terrakube.ui.url:}")
    private String uiUrl;

    public JobNotificationTrigger(NotificationConfigResolver notificationConfigResolver,
            NotificationPayloadRenderer notificationPayloadRenderer,
            NotificationOutboxRepository notificationOutboxRepository,
            NotificationDispatchService notificationDispatchService) {
        this.notificationConfigResolver = notificationConfigResolver;
        this.notificationPayloadRenderer = notificationPayloadRenderer;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationDispatchService = notificationDispatchService;
    }

    public List<UUID> enqueue(Job job) {
        if (job.getWorkspace() == null) {
            return List.of();
        }
        List<UUID> insertedIds = new ArrayList<>();
        try {
            List<NotificationConfiguration> configs = notificationConfigResolver.resolve(job.getWorkspace());
            for (NotificationConfiguration configuration : configs) {
                boolean matches = configuration.getTriggers() != null && configuration.getTriggers().stream()
                        .anyMatch(trigger -> trigger.getJobStatus() == job.getStatus());
                if (!matches) {
                    continue;
                }
                String payload = notificationPayloadRenderer.render(configuration.getChannelType(), buildContext(job));

                NotificationOutbox outbox = new NotificationOutbox();
                outbox.setId(UUID.randomUUID());
                outbox.setJob(job);
                outbox.setConfiguration(configuration);
                outbox.setPayload(payload);
                outbox.setStatus(NotificationOutboxStatus.PENDING);
                notificationOutboxRepository.save(outbox);
                insertedIds.add(outbox.getId());
            }
        } catch (Exception e) {
            // Never let a resolution/render failure fail the job status update itself.
            log.error("Failed to resolve/enqueue notifications for job {}", job.getId(), e);
        }
        return insertedIds;
    }

    public void notifyStatusChanged(Job job) {
        List<UUID> outboxIds = enqueue(job);
        if (outboxIds.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outboxIds.forEach(notificationDispatchService::dispatchAsync);
                }
            });
        } else {
            outboxIds.forEach(notificationDispatchService::dispatchAsync);
        }
    }

    NotificationContext buildContext(Job job) {
        String runUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                job.getOrganization().getId(), job.getWorkspace().getId(), job.getId());
        // job.output is the raw Terraform/OpenTofu run output; there is no dedicated
        // failure-reason field on Job, so this trims the tail of that log as a
        // best-effort summary rather than shipping the whole (possibly huge) output.
        String failureReason = null;
        if (job.getOutput() != null && !job.getOutput().isBlank()) {
            String output = job.getOutput();
            failureReason = output.length() > 500 ? output.substring(output.length() - 500) : output;
        }
        return new NotificationContext(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                job.getId(),
                job.getStatus(),
                runUrl,
                job.getCommitId(),
                failureReason);
    }
}
