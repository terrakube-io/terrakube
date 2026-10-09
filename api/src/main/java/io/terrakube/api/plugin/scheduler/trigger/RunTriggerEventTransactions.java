package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;

import lombok.extern.slf4j.Slf4j;

// A separate bean so every DB-mutating call goes through its own Spring transactional proxy,
// same reasoning as NotificationOutboxTransactions: a self-invoked call from
// RunTriggerEventDispatchService would bypass @Transactional, and RunTriggerEventPollerJob is a
// Quartz Job with no AOP proxy of its own at all.
@Slf4j
@Service
public class RunTriggerEventTransactions {

    private final RunTriggerEventRepository runTriggerEventRepository;
    private final RunTriggerEventMetrics metrics;

    RunTriggerEventTransactions(RunTriggerEventRepository runTriggerEventRepository, RunTriggerEventMetrics metrics) {
        this.runTriggerEventRepository = runTriggerEventRepository;
        this.metrics = metrics;
    }

    @Transactional
    ClaimedEvent claim(UUID eventId) {
        Date now = new Date();
        // Atomic conditional UPDATE, not a lock: only one concurrent caller's UPDATE can match
        // id + status = PENDING, so only one ever sees rows == 1 and proceeds.
        int rows = runTriggerEventRepository.claimForProcessing(eventId, RunTriggerEventStatus.PENDING,
                RunTriggerEventStatus.PROCESSING, now);
        if (rows == 0) {
            return null;
        }
        Optional<RunTriggerEvent> event = runTriggerEventRepository.findById(eventId);
        if (event.isEmpty()) {
            return null;
        }
        return new ClaimedEvent(event.get().getJob().getId(), event.get().getAttemptCount(),
                event.get().getLastAttemptAt());
    }

    @Transactional
    void recordResult(UUID eventId, Date expectedLastAttemptAt, RunTriggerEventStatus newStatus, String lastError,
            Date nextAttemptAt) {
        // Keyed on the exact lastAttemptAt from claim time: if the stuck-row sweep reclaimed
        // this row in the meantime, lastAttemptAt moved on and this becomes a no-op instead of
        // clobbering a newer attempt's result.
        int updated = runTriggerEventRepository.recordResult(eventId, RunTriggerEventStatus.PROCESSING,
                expectedLastAttemptAt, newStatus, lastError, nextAttemptAt, new Date());
        if (updated == 0) {
            log.warn("Discarding stale result for run trigger event {} - the row was reclaimed (stuck-row "
                    + "sweep) before this attempt finished; a duplicate dispatch attempt may have run", eventId);
        }
    }

    // A row stuck in PROCESSING means whatever claimed it died mid-dispatch before recordResult.
    // Called every poller tick so a crashed pod never permanently strands an event.
    @Transactional
    public void sweepStuckProcessingRows(Date cutoff, int maxAttempts) {
        int reclaimed = runTriggerEventRepository.reclaimStuckProcessingRows(RunTriggerEventStatus.PROCESSING,
                RunTriggerEventStatus.PENDING, cutoff, maxAttempts, new Date());
        int failed = runTriggerEventRepository.failStuckProcessingRowsAtMaxAttempts(RunTriggerEventStatus.PROCESSING,
                RunTriggerEventStatus.FAILED, cutoff, maxAttempts,
                "Processing attempt did not complete within the stuck-row threshold; the claiming instance "
                        + "likely crashed",
                new Date());
        if (reclaimed > 0) {
            metrics.stuckReclaimed(reclaimed);
        }
        if (failed > 0) {
            metrics.failed();
        }
        if (reclaimed > 0 || failed > 0) {
            log.warn("Run trigger event sweep reclaimed {} stuck PROCESSING row(s) for retry and permanently "
                    + "failed {} that had already exhausted their attempts", reclaimed, failed);
        }
    }

    // Would back an operator-triggered replay of a permanently failed event, mirroring
    // NotificationOutboxTransactions and the admin REST endpoint it has. No such endpoint exists
    // for run_trigger_event yet, so this currently has zero callers - kept public (unlike
    // claim/recordResult) in anticipation of one, not because one already calls it.
    @Transactional
    public boolean rearmForRetry(UUID eventId) {
        int rows = runTriggerEventRepository.rearmFailedForRetry(eventId, RunTriggerEventStatus.FAILED,
                RunTriggerEventStatus.PENDING, new Date());
        return rows > 0;
    }

    // Housekeeping: without this, run_trigger_event grows forever.
    @Transactional
    public int pruneTerminalRowsOlderThan(Date cutoff) {
        int deleted = runTriggerEventRepository.deleteTerminalRowsCreatedBefore(
                List.of(RunTriggerEventStatus.PROCESSED, RunTriggerEventStatus.FAILED), cutoff);
        if (deleted > 0) {
            metrics.pruned(deleted);
            log.info("Run trigger event retention sweep deleted {} row(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
