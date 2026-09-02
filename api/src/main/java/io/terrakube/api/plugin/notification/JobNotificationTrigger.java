package io.terrakube.api.plugin.notification;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.template.Template;
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

    // Statuses a run can still leave for a non-error status afterwards. A job briefly flips to
    // "failed" when the API↔executor dispatch call throws (see ScheduleJob.errorJobAtStep) even
    // though the executor often accepted the job and is running it - its next status callback
    // (setRunningStatus / setCompletedStatus) then moves the job back off "failed" and the run
    // completes normally. A notification for such a status is held back briefly and re-validated
    // against the job's live status before it is actually delivered (see
    // NotificationOutboxTransactions.claim), so these transient flips never reach the channel.
    private static final Set<JobStatus> RECOVERABLE_STATUSES = Set.of(JobStatus.failed);

    private final NotificationConfigResolver notificationConfigResolver;
    private final NotificationPayloadRenderer notificationPayloadRenderer;
    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationDispatchService notificationDispatchService;
    private final JobFailureSummaryService jobFailureSummaryService;

    @Value("${io.terrakube.ui.url:}")
    private String uiUrl;

    // How long to hold a notification for a potentially-transient status (RECOVERABLE_STATUSES)
    // before the poller may deliver it, giving the executor's own status callback time to
    // correct a dispatch-error flip. Only delays the delivery of these rows; every other status
    // is still dispatched immediately.
    @Value("${io.terrakube.notification.recoverableStatusGraceSeconds:90}")
    private long recoverableStatusGraceSeconds;

    public JobNotificationTrigger(NotificationConfigResolver notificationConfigResolver,
            NotificationPayloadRenderer notificationPayloadRenderer,
            NotificationOutboxRepository notificationOutboxRepository,
            NotificationDispatchService notificationDispatchService,
            JobFailureSummaryService jobFailureSummaryService) {
        this.notificationConfigResolver = notificationConfigResolver;
        this.notificationPayloadRenderer = notificationPayloadRenderer;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationDispatchService = notificationDispatchService;
        this.jobFailureSummaryService = jobFailureSummaryService;
    }

    // Returns the ids of rows that are due for immediate dispatch. A row rendered for a
    // potentially-transient status (RECOVERABLE_STATUSES) is still inserted, but with a short
    // nextAttemptAt in the future and its id withheld from the result - only the outbox poller
    // picks it up, after the grace window, and only if the job's live status still matches by
    // then. Callers dispatch exactly what is returned; the poller covers the rest.
    public List<UUID> enqueue(Job job) {
        if (job.getWorkspace() == null) {
            return List.of();
        }
        List<UUID> insertedIds = new ArrayList<>();
        try {
            boolean deferDispatch = RECOVERABLE_STATUSES.contains(job.getStatus());
            List<NotificationConfiguration> configs = notificationConfigResolver.resolve(job.getWorkspace());
            for (NotificationConfiguration configuration : configs) {
                boolean statusMatches = configuration.getTriggers() != null && configuration.getTriggers().stream()
                        .anyMatch(trigger -> trigger.getJobStatus() == job.getStatus());
                if (!statusMatches || !templateMatches(configuration, job)) {
                    continue;
                }
                UUID outboxId = saveOutboxRow(job, configuration, deferDispatch);
                if (!deferDispatch) {
                    insertedIds.add(outboxId);
                }
            }
        } catch (Exception e) {
            // Never let a resolution/render failure fail the job status update itself.
            log.error("Failed to resolve/enqueue notifications for job {}", job.getId(), e);
        }
        return insertedIds;
    }

    // Renders and persists one PENDING outbox row. A deferred row (job in a RECOVERABLE_STATUS)
    // gets a nextAttemptAt in the future so only the poller picks it up, after the grace window.
    private UUID saveOutboxRow(Job job, NotificationConfiguration configuration, boolean deferred) {
        String payload = notificationPayloadRenderer.render(configuration.getChannelType(),
                buildContext(job, configuration));

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(job);
        outbox.setConfiguration(configuration);
        outbox.setPayload(payload);
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        outbox.setJobStatus(job.getStatus());
        if (deferred) {
            outbox.setNextAttemptAt(
                    new Date(System.currentTimeMillis() + recoverableStatusGraceSeconds * 1000L));
        }
        notificationOutboxRepository.save(outbox);
        return outbox.getId();
    }

    // Empty/null templates means "applies to every template" - this only ever narrows which
    // templates a configuration fires for, never widens it, so existing configurations (no
    // templates selected) keep matching every job exactly as before this filter existed.
    private boolean templateMatches(NotificationConfiguration configuration, Job job) {
        List<Template> templates = configuration.getTemplates();
        if (templates == null || templates.isEmpty()) {
            return true;
        }
        return templates.stream().anyMatch(template -> template.getId().toString().equals(job.getTemplateReference()));
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

    NotificationContext buildContext(Job job, NotificationConfiguration configuration) {
        String workspaceUrl = String.format("%s/organizations/%s/workspaces/%s", uiUrl,
                job.getOrganization().getId(), job.getWorkspace().getId());
        String runUrl = workspaceUrl + "/runs/" + job.getId();
        // Only a genuinely failed job carries a failure reason; for any other status there is
        // nothing to explain. The text is the tail of the failing step's console output (see
        // JobFailureSummaryService) - job.output itself only holds "Step <id> completed"
        // markers and is useless here.
        String failureReason = job.getStatus() == JobStatus.failed
                ? jobFailureSummaryService.describeFailure(job)
                : null;
        return new NotificationContext(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                job.getId(),
                job.getStatus(),
                runUrl,
                job.getCommitId(),
                failureReason,
                configuration.getName(),
                workspaceUrl,
                configuration.getMessageStyle());
    }
}
