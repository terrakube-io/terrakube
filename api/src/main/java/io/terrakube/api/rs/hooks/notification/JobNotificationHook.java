package io.terrakube.api.rs.hooks.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.rs.job.Job;

import lombok.extern.slf4j.Slf4j;

// Covers job status changes that happen as an actual Elide JSON:API/GraphQL UPDATE (e.g. a
// direct API PATCH). Most real run status transitions (queue -> pending -> running -> ...) never
// go through Elide at all - see JobNotificationTrigger's javadoc - so ScheduleJob/ScheduleJobTrigger
// call JobNotificationTrigger.notifyStatusChanged() directly instead of relying on this hook.
@Slf4j
@Component
public class JobNotificationHook implements LifeCycleHook<Job> {

    @Autowired
    JobNotificationTrigger jobNotificationTrigger;

    @Autowired
    NotificationDispatchService notificationDispatchService;

    // Keyed by job id (not a flat list) because a single Elide transaction can update more than
    // one Job entity's status on the same thread - Elide fires PRECOMMIT/POSTCOMMIT once per
    // updated entity, so a flat, reset-on-every-PRECOMMIT list would have a second job's
    // PRECOMMIT overwrite the first job's still-undispatched outbox IDs before its POSTCOMMIT
    // ever ran.
    private static final ThreadLocal<Map<Integer, List<UUID>>> PENDING_OUTBOX_IDS_BY_JOB = ThreadLocal
            .withInitial(HashMap::new);

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope requestScope,
            Optional<ChangeSpec> changes) {
        if (operation != Operation.UPDATE || job.getWorkspace() == null) {
            return;
        }
        switch (phase) {
            case PRECOMMIT -> handlePrecommit(job);
            case POSTCOMMIT -> handlePostcommit(job);
            default -> {
            }
        }
    }

    private void handlePrecommit(Job job) {
        // Entry per job id (not a reset of a single shared slot) so a prior PRECOMMIT for a
        // DIFFERENT job on this thread whose POSTCOMMIT hasn't run yet keeps its own pending
        // outbox IDs. A prior PRECOMMIT for the SAME job id whose POSTCOMMIT never ran (e.g. the
        // transaction rolled back) is still safely overwritten here, since it can't have any
        // rows worth dispatching left over.
        PENDING_OUTBOX_IDS_BY_JOB.get().put(job.getId(), jobNotificationTrigger.enqueue(job));
    }

    private void handlePostcommit(Job job) {
        Map<Integer, List<UUID>> pending = PENDING_OUTBOX_IDS_BY_JOB.get();
        List<UUID> outboxIds = pending.remove(job.getId());
        try {
            if (outboxIds == null) {
                return;
            }
            for (UUID outboxId : outboxIds) {
                try {
                    notificationDispatchService.dispatchAsync(outboxId);
                } catch (RejectedExecutionException e) {
                    // The dispatch executor's queue is full - the row is still PENDING (this
                    // submission never claimed it), so the outbox poller will pick it up on its
                    // next tick. Never let this escape into the Elide POSTCOMMIT lifecycle.
                    log.warn("Notification dispatch executor rejected outbox {}, will be picked up by the poller",
                            outboxId);
                }
            }
        } finally {
            if (pending.isEmpty()) {
                PENDING_OUTBOX_IDS_BY_JOB.remove();
            }
        }
    }
}
