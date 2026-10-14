package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.history.History;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.ObjectAlreadyExistsException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Processes a durable successful-job event. Flow and state identity decide whether the event
 * contributes an eligible change; the coordinator resolves every snapshotted dependency,
 * including no-change and leaf completions. Each destination's job and attempt commit before
 * this non-transactional service schedules it. Event replay and scheduler reconciliation repair
 * scheduling failures without appending another attempt.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RunTriggerDispatchService {

    private static final Set<String> TERRAFORM_STATE_FLOWS = Set.of(
            FlowType.terraformApply.toString(),
            FlowType.terraformDestroy.toString());

    /**
     * Flow types that change remote state. A run made only of plans leaves nothing for a
     * dependent to react to, so it does not fire triggers. Mirrors the qualification
     * ScheduleJob.isActiveApplyOrDestroyRunning already applies for queue admission.
     */
    private static final Set<String> STATE_CHANGING_FLOWS = Set.of(
            FlowType.terraformApply.toString(),
            FlowType.terraformDestroy.toString(),
            FlowType.customScripts.toString());

    private final JobRepository jobRepository;
    private final StepRepository stepRepository;
    private final WorkspaceRunTriggerRepository workspaceRunTriggerRepository;
    private final HistoryRepository historyRepository;
    private final TclService tclService;
    private final JobNotificationTrigger jobNotificationTrigger;
    private final ScheduleJobService scheduleJobService;
    private final RunTriggerProperties properties;
    private final RunTriggerDispatchMetrics runTriggerDispatchMetrics;
    private final RunCascadeCoordinatorService cascadeCoordinatorService;

    /**
     * Fans out from a job that has just completed. Takes the id rather than the entity because
     * the caller invokes this after its own transaction has committed: re-reading here keeps
     * every association attached to a live session instead of relying on what happened to be
     * initialised upstream.
     *
     * <p>Never throws. The upstream run is already finished and recorded; a dispatch problem
     * is an operational event to be logged, not a reason to surface an error on a run that
     * succeeded.
     */
    public void dispatchFor(int completedJobId) {
        try {
            dispatchInternal(completedJobId);
        } catch (Exception e) {
            log.error("Run trigger dispatch failed for job {}: {}", completedJobId, e.getMessage(), e);
        }
    }

    /**
     * Package-private so the event worker can call it directly and let a failure propagate for
     * it to record and retry, instead of being swallowed as {@link #dispatchFor}'s callers see.
     */
    void dispatchInternal(int completedJobId) {
        if (!properties.isEnabled()) {
            return;
        }

        Job completedJob = jobRepository.findById(completedJobId).orElse(null);
        if (completedJob == null || completedJob.getWorkspace() == null) {
            return;
        }

        // Fetched once and classified once: every completed step's flow type is read from its
        // job's TCL (a full YAML parse, no caching) exactly one time each, shared by both the
        // state-changed check and the onDestroy check below, instead of two separate per-step
        // loops each paying that parse again.
        List<Step> steps = stepRepository.findByJobId(completedJobId);
        StepFlowClassification classification = classifyCompletedSteps(completedJob, steps);
        boolean stateChanged = changedState(completedJob, classification);
        cascadeCoordinatorService.resolveNode(completedJob, stateChanged);
        boolean wasDestroy = classification.wasDestroy();
        int nextDepth = completedJob.getCascadeDepth() + 1;
        // Leaf and no-change jobs still resolve their existing snapshot. Only independent
        // runs without dependencies can return before creating an execution.
        RunCascade cascade = cascadeCoordinatorService.findCascade(completedJob.getId()).orElse(null);
        if (cascade == null) {
            if (!stateChanged || nextDepth > properties.getMaxCascadeDepth()
                    || workspaceRunTriggerRepository.findEnabledBySourceWorkspaceId(completedJob.getWorkspace().getId()).isEmpty()) return;
            cascade = cascadeCoordinatorService.ensureCascade(completedJob);
        }
        List<WorkspaceRunTrigger> triggers = cascadeCoordinatorService.snapshotTriggers(
                cascade.getId(), completedJob.getWorkspace().getId());

        // Tracked rather than logged-and-forgotten: a dependent that failed to enqueue must
        // still fail the batch so the caller (RunTriggerEventDispatchService) retries this job
        // with backoff instead of marking the outbox event PROCESSED. Re-running the loop is
        // safe because the coordinator finds the existing attempt under the cascade lock.
        boolean partialFailure = false;

        for (WorkspaceRunTrigger trigger : triggers) {
            try {
                Job created = cascadeCoordinatorService.enqueue(cascade, trigger, completedJob, nextDepth,
                        wasDestroy, stateChanged && nextDepth <= properties.getMaxCascadeDepth());
                if (created != null && created.getStatus() == JobStatus.pending) {
                    try {
                        jobNotificationTrigger.notifyStatusChanged(created);
                        scheduleJobService.createJobContext(created);
                    } catch (ObjectAlreadyExistsException ignored) {
                        // Event replay or the scheduler sweep already scheduled this durable job.
                    }
                }
            } catch (Exception e) {
                partialFailure = true;
                log.error("Could not enqueue run triggered by job {} on workspace {}: {}",
                        completedJobId, destinationName(trigger), e.getMessage(), e);
            }
        }

        cascadeCoordinatorService.recomputeStatus(cascade.getId());

        if (partialFailure) {
            throw new RunTriggerDispatchPartialFailureException(
                    "One or more run triggers failed to dispatch for job " + completedJobId);
        }
    }

    /**
     * One pass over a job's completed steps, reading each step's flow type exactly once - the
     * single shared source for both {@link #changedState} and whether this run was a destroy,
     * rather than each re-deriving it with its own loop over the same steps.
     */
    private record StepFlowClassification(boolean hasStateChangingFlow, boolean hasTerraformFlow,
            boolean wasDestroy) {
    }

    private StepFlowClassification classifyCompletedSteps(Job job, List<Step> steps) {
        boolean hasStateChangingFlow = false;
        boolean hasTerraformFlow = false;
        boolean wasDestroy = false;

        for (Step step : steps) {
            if (step.getStatus() != JobStatus.completed) {
                continue;
            }
            String flowType = tclService.getFlowTypeForStep(job, step.getStepNumber());
            if (flowType == null) {
                continue;
            }
            if (TERRAFORM_STATE_FLOWS.contains(flowType)) {
                hasTerraformFlow = true;
                hasStateChangingFlow = true;
            } else if (STATE_CHANGING_FLOWS.contains(flowType)) {
                hasStateChangingFlow = true;
            }
            // Not terraformPlanDestroy, which only plans destroying this same workspace and
            // has nothing to do with what an edge's onDestroy policy means for a dependent.
            if (FlowType.terraformDestroy.toString().equals(flowType)) {
                wasDestroy = true;
            }
        }

        return new StepFlowClassification(hasStateChangingFlow, hasTerraformFlow, wasDestroy);
    }

    /**
     * True when at least one step that actually ran changed remote state.
     * 1. A state-changing flow type (apply, destroy, customScripts) must have completed.
     * 2. For Terraform flows (apply, destroy), state identity (serial, md5) is compared
     *    against the baseline immediately prior to this state's write time.
     *    If serial did not increase and md5 did not change, remote state was untouched.
     */
    private boolean changedState(Job job, StepFlowClassification classification) {
        if (!classification.hasStateChangingFlow()) {
            return false;
        }

        if (!classification.hasTerraformFlow()) {
            return true;
        }

        Optional<History> jobHistory = historyRepository
                .findFirstByWorkspaceAndJobReferenceOrderByCreatedDateDesc(
                        job.getWorkspace(), String.valueOf(job.getId()));

        if (jobHistory.isEmpty()) {
            runTriggerDispatchMetrics.stateIdentityFallback();
            log.debug("Job {} completed state-changing flow but no history record was found, falling back to true", job.getId());
            return true;
        }

        Optional<History> baseline = historyRepository
                .findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(
                        job.getWorkspace(), jobHistory.get().getCreatedDate());

        if (baseline.isEmpty()) {
            log.debug("Job {} produced the first state record for workspace {}", job.getId(), job.getWorkspace().getId());
            return true;
        }

        // Backward compatibility fallback for legacy history records written before real serial/md5
        // were captured (where serial was 1 and md5 was "0").
        if (jobHistory.get().getSerial() <= 1 && "0".equals(jobHistory.get().getMd5())) {
            runTriggerDispatchMetrics.stateIdentityFallback();
            log.debug("Job {} produced legacy state record (serial <= 1, md5 = 0), falling back to true", job.getId());
            return true;
        }

        boolean serialChanged = jobHistory.get().getSerial() > baseline.get().getSerial();
        boolean md5Changed = !Objects.equals(jobHistory.get().getMd5(), "0")
                && !Objects.equals(jobHistory.get().getMd5(), baseline.get().getMd5());

        boolean changed = serialChanged || md5Changed;
        log.debug("Job {} state evaluation: serialChanged={}, md5Changed={}, changed={}",
                job.getId(), serialChanged, md5Changed, changed);
        return changed;
    }

    private static String destinationName(WorkspaceRunTrigger trigger) {
        Workspace destination = trigger.getDestinationWorkspace();
        return destination != null ? destination.getName() : "unknown";
    }
}
