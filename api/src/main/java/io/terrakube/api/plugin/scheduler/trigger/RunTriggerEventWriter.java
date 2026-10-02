package io.terrakube.api.plugin.scheduler.trigger;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes the durable {@code RunTriggerEvent} row for a job that just completed. Called from
 * inside {@code JobReconciliationService}'s own transaction, not an {@code afterCommit}
 * callback, so the status change and the event row either land together or neither happens.
 *
 * <p>Qualification here is deliberately cheap and conservative; everything more expensive
 * (state identity, cascade depth, per-edge resolution) stays in {@link RunTriggerDispatchService},
 * evaluated once the event is processed. Writing an unnecessary event is harmless - the worker
 * just finds nothing to do - but skipping a job that did need one is the failure mode to avoid.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RunTriggerEventWriter {

    private final RunTriggerEventRepository runTriggerEventRepository;
    private final WorkspaceRunTriggerRepository workspaceRunTriggerRepository;
    private final RunTriggerProperties properties;

    public void enqueueIfQualifying(Job completedJob) {
        if (!properties.isEnabled()) {
            return;
        }
        if (completedJob == null || completedJob.getWorkspace() == null) {
            return;
        }

        // Existence check, not the full fan-out list: a workspace with no enabled edges gets no row.
        boolean hasOutboundEdges = workspaceRunTriggerRepository
                .existsBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(
                        completedJob.getWorkspace().getId());
        if (!hasOutboundEdges) {
            return;
        }

        // Belt-and-suspenders ahead of the unique constraint on job_id - reconcile() should
        // only reach this once per job, but the DB constraint is the real guarantee.
        if (runTriggerEventRepository.existsByJob_Id(completedJob.getId())) {
            return;
        }

        RunTriggerEvent event = new RunTriggerEvent();
        event.setId(UUID.randomUUID());
        event.setJob(completedJob);
        event.setStatus(RunTriggerEventStatus.PENDING);
        try {
            runTriggerEventRepository.save(event);
        } catch (DataIntegrityViolationException e) {
            // The unique constraint caught a race the existence check above missed - success either way.
            log.debug("Run trigger event for job {} already exists, skipping duplicate insert",
                    completedJob.getId());
        }
    }
}
