package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.NotificationConfigResolver;
import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * Same shape as {@link io.terrakube.api.plugin.notification.JobNotificationTrigger}, keyed on a
 * cascade's own outcome instead of a single job's status - resolved against the cascade's origin
 * workspace's {@link NotificationConfiguration}s, matched against each one's
 * {@code cascadeStatus}-bearing triggers (see {@code NotificationTrigger}). Only ever called for
 * a terminal-for-now outcome (COMPLETED/DEGRADED/BLOCKED/CANCELLED) - never RUNNING, which every
 * cascade starts as and would otherwise fire on.
 */
@Slf4j
@Service
public class RunCascadeNotificationTrigger {

    private final NotificationConfigResolver notificationConfigResolver;
    private final NotificationPayloadRenderer notificationPayloadRenderer;
    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationDispatchService notificationDispatchService;
    private final RunCascadeNodeRepository runCascadeNodeRepository;

    @Value("${io.terrakube.ui.url:}")
    private String uiUrl;

    public RunCascadeNotificationTrigger(NotificationConfigResolver notificationConfigResolver,
            NotificationPayloadRenderer notificationPayloadRenderer,
            NotificationOutboxRepository notificationOutboxRepository,
            NotificationDispatchService notificationDispatchService,
            RunCascadeNodeRepository runCascadeNodeRepository) {
        this.notificationConfigResolver = notificationConfigResolver;
        this.notificationPayloadRenderer = notificationPayloadRenderer;
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationDispatchService = notificationDispatchService;
        this.runCascadeNodeRepository = runCascadeNodeRepository;
    }

    public void notifyCascadeStatusChanged(RunCascade cascade) {
        Job originJob = cascade.getOriginJob();
        Workspace originWorkspace = originJob == null ? null : originJob.getWorkspace();
        if (originWorkspace == null) {
            return;
        }

        List<UUID> outboxIds = new ArrayList<>();
        try {
            List<NotificationConfiguration> configs = notificationConfigResolver.resolve(originWorkspace);
            for (NotificationConfiguration configuration : configs) {
                boolean statusMatches = configuration.getTriggers() != null && configuration.getTriggers().stream()
                        .anyMatch(trigger -> trigger.getCascadeStatus() == cascade.getStatus());
                if (!statusMatches || !templateMatches(configuration, originJob)) {
                    continue;
                }
                outboxIds.add(saveOutboxRow(cascade, originJob, configuration));
            }
        } catch (Exception e) {
            // Never let a resolution/render failure block the cascade status transition itself.
            log.error("Failed to resolve/enqueue cascade notifications for cascade {}", cascade.getId(), e);
            return;
        }
        if (outboxIds.isEmpty()) {
            return;
        }

        // Same deferred-dispatch-until-commit reasoning as JobNotificationTrigger.
        // notifyStatusChanged: the outbox row insert isn't visible to the async dispatch thread's
        // own connection until the caller's transaction (recomputeStatus/cancelCascade, both
        // @Transactional) commits.
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

    private boolean templateMatches(NotificationConfiguration configuration, Job originJob) {
        List<Template> templates = configuration.getTemplates();
        if (templates == null || templates.isEmpty()) {
            return true;
        }
        return templates.stream()
                .anyMatch(template -> template.getId().toString().equals(originJob.getTemplateReference()));
    }

    private UUID saveOutboxRow(RunCascade cascade, Job originJob, NotificationConfiguration configuration) {
        String payload = notificationPayloadRenderer.render(configuration.getChannelType(),
                buildContext(cascade, originJob, configuration));

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(originJob);
        outbox.setConfiguration(configuration);
        outbox.setPayload(payload);
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        // jobStatus is left null on purpose: NotificationOutboxTransactions.claim only re-checks
        // a row against the job's *live* status when jobStatus is set, treating null as "always
        // send" - correct here, since this row describes the cascade's own (terminal) outcome,
        // not a point-in-time job status that could have since moved on.
        notificationOutboxRepository.save(outbox);
        return outbox.getId();
    }

    private NotificationContext buildContext(RunCascade cascade, Job originJob, NotificationConfiguration configuration) {
        String workspaceUrl = String.format("%s/organizations/%s/workspaces/%s", uiUrl,
                originJob.getOrganization().getId(), originJob.getWorkspace().getId());
        String runUrl = workspaceUrl + "/runs/" + originJob.getId();
        return new NotificationContext(
                originJob.getOrganization().getName(),
                originJob.getWorkspace().getName(),
                originJob.getId(),
                null,
                runUrl,
                originJob.getCommitId(),
                null,
                configuration.getName(),
                workspaceUrl,
                configuration.getMessageStyle(),
                cascade.getStatus(),
                summarize(cascade));
    }

    private String summarize(RunCascade cascade) {
        List<RunCascadeNode> nodes = runCascadeNodeRepository.findByCascade_Id(cascade.getId());
        long succeeded = countByStatus(nodes, RunCascadeNodeStatus.SUCCEEDED);
        long failed = countByStatus(nodes, RunCascadeNodeStatus.FAILED);
        long blocked = countByStatus(nodes, RunCascadeNodeStatus.BLOCKED);
        long skipped = countByStatus(nodes, RunCascadeNodeStatus.SKIPPED);
        long cancelled = countByStatus(nodes, RunCascadeNodeStatus.CANCELLED);
        long pending = nodes.size() - succeeded - failed - blocked - skipped - cancelled;

        StringBuilder summary = new StringBuilder()
                .append(nodes.size()).append(nodes.size() == 1 ? " workspace" : " workspaces")
                .append(" in cascade: ").append(succeeded).append(" succeeded");
        if (failed > 0) {
            summary.append(", ").append(failed).append(" failed");
        }
        if (blocked > 0) {
            summary.append(", ").append(blocked).append(" blocked");
        }
        if (skipped > 0) {
            summary.append(", ").append(skipped).append(" skipped");
        }
        if (cancelled > 0) {
            summary.append(", ").append(cancelled).append(" cancelled");
        }
        if (pending > 0) {
            summary.append(", ").append(pending).append(" pending");
        }
        return summary.toString();
    }

    private long countByStatus(List<RunCascadeNode> nodes, RunCascadeNodeStatus status) {
        return nodes.stream().filter(n -> n.getStatus() == status).count();
    }
}
