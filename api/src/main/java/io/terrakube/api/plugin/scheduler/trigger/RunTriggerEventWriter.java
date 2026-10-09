package io.terrakube.api.plugin.scheduler.trigger;

import java.util.UUID;

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

        // Not actually racy: JobReconciliationService.reconcile() takes jobRepository.lockForUpdate
        // on the upstream job before calling here, so no concurrent caller can reach this existence
        // check for the same job while another is between it and the save below. The unique
        // constraint on job_id is the real guarantee against a row written outside that path, not
        // a race this method needs to catch - a constraint violation here is a genuine bug.
        if (runTriggerEventRepository.existsByJob_Id(completedJob.getId())) {
            return;
        }

        RunTriggerEvent event = new RunTriggerEvent();
        event.setId(UUID.randomUUID());
        event.setJob(completedJob);
        event.setStatus(RunTriggerEventStatus.PENDING);
        runTriggerEventRepository.save(event);
    }
}
