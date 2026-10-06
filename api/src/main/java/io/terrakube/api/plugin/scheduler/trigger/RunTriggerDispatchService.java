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
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
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
 * Enqueues the downstream runs of a workspace whose run just changed state.
 *
 * <p>Called by {@link io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationService}
 * after it commits a job's transition to {@code completed}, which is the one routine in the
 * system that performs that transition. That placement is what gives dispatch its
 * exactly-once property: the transition is taken under a row lock and an already-terminal job
 * short-circuits, so only the caller that actually moved the job reaches this service, even
 * with several API replicas racing on the same job.
 *
 * <p>Deliberately not transactional. Each dependent is committed on its own by
 * {@link RunTriggerJobWriter}, and the Quartz trigger for it is created only afterwards: while
 * an insert is still open its row is invisible to the Quartz worker, which reads on another
 * connection and treats a job it cannot find as deleted. Holding one transaction across the
 * fan-out would also let a single failed insert roll back every job created beside it, since
 * catching the exception does not clear the rollback-only mark.
 *
 * <p>The reads it does need no outer transaction: the completed job materialises its workspace
 * and organization eagerly, and the dispatch query fetches destination, organization and
 * template through its entity graph.
 *
 * <p>{@link RunCascadeCoordinatorService} is consulted per edge for {@code ANY}/{@code ALL}
 * readiness and cascade bookkeeping, but owns none of the enqueue mechanics above - {@code EACH}
 * is never gated by it at all, which is what keeps this unchanged for every edge that predates it.
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
    private final RunTriggerJobWriter jobWriter;
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

        // Always, regardless of what follows: a node that produced no further change still
        // succeeded, and must not stay RUNNING forever waiting for a fan-out that was never
        // coming. A no-op for a job outside any cascade, which is still most of them today.
        cascadeCoordinatorService.resolveNode(completedJob);

        if (!changedState(completedJob)) {
            log.debug("Job {} changed no state, no run trigger dispatched", completedJobId);
            return;
        }

        // Checked before loading the edges: at the limit the answer is the same for every
        // dependent, and saying so once is more useful than repeating it per edge.
        int nextDepth = completedJob.getCascadeDepth() + 1;
        if (nextDepth > properties.getMaxCascadeDepth()) {
            log.warn("Job {} reached the run trigger cascade limit of {}, not dispatching further",
                    completedJobId, properties.getMaxCascadeDepth());
            return;
        }

        // No cap here: WorkspaceGraphValidationService.validateFanOutLimit keeps a workspace
        // from ever acquiring more than the configured limit, so every edge below dispatches.
        List<WorkspaceRunTrigger> triggers = workspaceRunTriggerRepository
                .findEnabledBySourceWorkspaceId(completedJob.getWorkspace().getId());
        if (triggers.isEmpty()) {
            return;
        }

        // Tracked rather than logged-and-forgotten: a dependent that failed to enqueue must
        // still fail the batch so the caller (RunTriggerEventDispatchService) retries this job
        // with backoff instead of marking the outbox event PROCESSED. Re-running the loop is
        // safe because enqueue() is idempotent per (destination, completedJob) - see below.
        boolean partialFailure = false;

        // Snapshots the whole reachable subgraph once, up front, so an ALL join further down
        // the graph can see every parent it depends on - not just the ones below this one edge.
        RunCascade cascade = cascadeCoordinatorService.ensureCascade(completedJob);

        for (WorkspaceRunTrigger trigger : triggers) {
            // Per-dependent isolation: one workspace missing a template, or failing to
            // schedule, must not cost the dependents that come after it in the list.
            try {
                // EACH (the only mode before this) is unconditionally true here - never gated.
                if (!cascadeCoordinatorService.shouldDispatch(cascade, trigger)) {
                    continue;
                }
                Job created = enqueue(trigger, completedJob, nextDepth);
                if (created != null) {
                    cascadeCoordinatorService.recordDispatch(cascade, trigger, created);
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
     * True when at least one step that actually ran changed remote state.
     * 1. A state-changing flow type (apply, destroy, customScripts) must have completed.
     * 2. For Terraform flows (apply, destroy), state identity (serial, md5) is compared
     *    against the baseline immediately prior to this state's write time.
     *    If serial did not increase and md5 did not change, remote state was untouched.
     */
    private boolean changedState(Job job) {
        List<Step> steps = stepRepository.findByJobId(job.getId());
        boolean hasStateChangingFlow = false;
        boolean hasTerraformFlow = false;

        for (Step step : steps) {
            if (step.getStatus() != JobStatus.completed) {
                continue;
            }
            String flowType = tclService.getFlowTypeForStep(job, step.getStepNumber());
            if (flowType != null) {
                if (TERRAFORM_STATE_FLOWS.contains(flowType)) {
                    hasTerraformFlow = true;
                    hasStateChangingFlow = true;
                } else if (STATE_CHANGING_FLOWS.contains(flowType)) {
                    hasStateChangingFlow = true;
                }
            }
        }

        if (!hasStateChangingFlow) {
            return false;
        }

        if (!hasTerraformFlow) {
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

    /** @return the job created, or null if this dependent had no template to run and was skipped. */
    private Job enqueue(WorkspaceRunTrigger trigger, Job completedJob, int depth) throws Exception {
        Workspace destination = trigger.getDestinationWorkspace();

        // Idempotency key for this fan-out edge: a RunTriggerEvent can be reclaimed and
        // reprocessed (lease expiry, a crashed pod, a slow recordResult) after some dependents
        // already committed, so dispatchInternal's loop must tolerate running twice for the
        // same completedJob without creating a second downstream job per destination.
        Optional<Job> existingJob = jobRepository
                .findFirstByWorkspaceAndTriggeredByJobIdOrderByIdDesc(destination, completedJob.getId());
        if (existingJob.isPresent()) {
            reconcilePreviouslyEnqueued(existingJob.get(), completedJob, destination);
            return;
        }

        // Resolved here rather than in the writer: it reads fields already loaded by the
        // dispatch query, so it costs nothing and keeps the writer to a single concern.
        String templateReference = resolveTemplate(trigger, destination);
        if (templateReference == null || templateReference.isBlank()) {
            log.warn("Run trigger {} has no template and workspace {} has no default template, skipping",
                    trigger.getId(), destination.getName());
            return null;
        }

        Job savedJob = jobWriter.persist(destination, templateReference, completedJob, depth);

        // Only now that the row is committed and visible to other connections. A plain
        // repository save also bypasses Elide, so neither JobNotificationHook nor JobManageHook
        // fires for this job and both side effects have to be raised by hand, exactly as
        // ScheduleJobTrigger does for scheduled runs.
        jobNotificationTrigger.notifyStatusChanged(savedJob);
        scheduleJobService.createJobContext(savedJob);

        log.info("Job {} triggered job {} on workspace {} (depth {})",
                completedJob.getId(), savedJob.getId(), destination.getName(), depth);
        return savedJob;
    }

    /**
     * A job already exists for this (destination, completedJob) pair from an earlier attempt
     * at this same dispatch. Two cases, distinguished by status rather than by any flag of our
     * own, because Quartz's job store - not this table - is the real source of truth for
     * whether the dependent is actually scheduled:
     *
     * <p>Still {@code pending}: the row committed on a prior attempt but that attempt did not
     * get as far as recording a result (died before, or failed during, {@code createJobContext}).
     * Re-running the scheduling step is safe either way: if Quartz never got the job, this is
     * what finally schedules it; if Quartz already has it, {@code scheduler.scheduleJob} throws
     * {@link ObjectAlreadyExistsException}, which is treated as confirmation rather than a
     * failure (the same tolerance {@code JobReconciliationService} applies when it removes a
     * trigger).
     *
     * <p>Anything else: the dependent already ran its course on a prior attempt. Nothing to do.
     */
    private void reconcilePreviouslyEnqueued(Job existingJob, Job completedJob, Workspace destination)
            throws Exception {
        if (existingJob.getStatus() != JobStatus.pending) {
            log.info("Job {} already triggered job {} on workspace {} (status {}), skipping duplicate dispatch",
                    completedJob.getId(), existingJob.getId(), destination.getName(), existingJob.getStatus());
            return;
        }
        try {
            scheduleJobService.createJobContext(existingJob);
            log.info("Re-scheduled previously persisted job {} on workspace {} triggered by job {}",
                    existingJob.getId(), destination.getName(), completedJob.getId());
        } catch (ObjectAlreadyExistsException e) {
            log.debug("Job {} on workspace {} already has a Quartz context, nothing to do",
                    existingJob.getId(), destination.getName());
        }
    }

    /**
     * The trigger's own template wins, then the destination's default. A locked destination is
     * deliberately not special-cased: the job is enqueued as pending and ScheduleJob admits it
     * once the lock clears, because skipping would leave the dependent silently drifted, which
     * is the very thing run triggers exist to prevent.
     */
    private String resolveTemplate(WorkspaceRunTrigger trigger, Workspace destination) {
        if (trigger.getTemplate() != null) {
            return trigger.getTemplate().getId().toString();
        }
        return destination.getDefaultTemplate();
    }

    private static String destinationName(WorkspaceRunTrigger trigger) {
        Workspace destination = trigger.getDestinationWorkspace();
        return destination != null ? destination.getName() : "unknown";
    }
}
