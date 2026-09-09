package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
 * <p>Runs in its own transaction after the commit rather than inside it. A trigger that
 * cannot be dispatched must never roll back the completion of the run that fired it, and the
 * Quartz context created for each downstream job is not transactional to begin with.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RunTriggerDispatchService {

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
    private final TclService tclService;
    private final JobNotificationTrigger jobNotificationTrigger;
    private final ScheduleJobService scheduleJobService;
    private final RunTriggerProperties properties;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchFor(int completedJobId) {
        try {
            dispatchInternal(completedJobId);
        } catch (Exception e) {
            log.error("Run trigger dispatch failed for job {}: {}", completedJobId, e.getMessage(), e);
        }
    }

    private void dispatchInternal(int completedJobId) {
        if (!properties.isEnabled()) {
            return;
        }

        Job completedJob = jobRepository.findById(completedJobId).orElse(null);
        if (completedJob == null || completedJob.getWorkspace() == null) {
            return;
        }

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

        List<WorkspaceRunTrigger> triggers = workspaceRunTriggerRepository
                .findEnabledBySourceWorkspaceId(completedJob.getWorkspace().getId());
        if (triggers.isEmpty()) {
            return;
        }

        List<WorkspaceRunTrigger> dispatchable = applyFanOutLimit(triggers, completedJob);

        for (WorkspaceRunTrigger trigger : dispatchable) {
            // Per-dependent isolation: one workspace missing a template, or failing to
            // schedule, must not cost the dependents that come after it in the list.
            try {
                enqueue(trigger, completedJob, nextDepth);
            } catch (Exception e) {
                log.error("Could not enqueue run triggered by job {} on workspace {}: {}",
                        completedJobId, destinationName(trigger), e.getMessage(), e);
            }
        }
    }

    /**
     * True when at least one step that actually ran changed remote state. Steps are checked
     * individually rather than trusting the job status: a plan/apply template whose apply was
     * never executed still finishes as completed, and has nothing downstream to react to.
     */
    private boolean changedState(Job job) {
        List<Step> steps = stepRepository.findByJobId(job.getId());
        for (Step step : steps) {
            if (step.getStatus() != JobStatus.completed) {
                continue;
            }
            String flowType = tclService.getFlowTypeForStep(job, step.getStepNumber());
            if (flowType != null && STATE_CHANGING_FLOWS.contains(flowType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Caps the fan-out, keeping a stable prefix so that a graph over the limit dispatches the
     * same dependents on every apply instead of a different arbitrary subset each time.
     */
    private List<WorkspaceRunTrigger> applyFanOutLimit(List<WorkspaceRunTrigger> triggers, Job completedJob) {
        int limit = properties.getMaxDependentsPerApply();
        if (triggers.size() <= limit) {
            return triggers;
        }

        List<WorkspaceRunTrigger> ordered = triggers.stream()
                .sorted(Comparator.comparing(t -> t.getDestinationWorkspace().getId()))
                .toList();
        List<WorkspaceRunTrigger> skipped = ordered.subList(limit, ordered.size());
        log.warn("Workspace {} has {} enabled run triggers, above the limit of {}. Job {} will not "
                        + "dispatch to: {}",
                completedJob.getWorkspace().getName(), triggers.size(), limit, completedJob.getId(),
                skipped.stream().map(RunTriggerDispatchService::destinationName).toList());
        return ordered.subList(0, limit);
    }

    private void enqueue(WorkspaceRunTrigger trigger, Job completedJob, int depth) throws Exception {
        Workspace destination = trigger.getDestinationWorkspace();

        String templateReference = resolveTemplate(trigger, destination);
        if (templateReference == null || templateReference.isBlank()) {
            log.warn("Run trigger {} has no template and workspace {} has no default template, skipping",
                    trigger.getId(), destination.getName());
            return;
        }

        Date now = new Date(System.currentTimeMillis());
        Job job = new Job();
        job.setWorkspace(destination);
        job.setOrganization(destination.getOrganization());
        job.setTemplateReference(templateReference);
        job.setStatus(JobStatus.pending);
        job.setRefresh(true);
        job.setPlanChanges(true);
        job.setRefreshOnly(false);
        job.setVia(JobVia.RUN_TRIGGER.getValue());
        job.setTriggeredByJobId(completedJob.getId());
        job.setCascadeDepth(depth);
        job.setCreatedBy("serviceAccount");
        job.setUpdatedBy("serviceAccount");
        job.setCreatedDate(now);
        job.setUpdatedDate(now);

        Job savedJob = jobRepository.save(job);

        // A plain repository save bypasses Elide, so neither JobNotificationHook nor
        // JobManageHook fires for this job: the status event and the Quartz context both have
        // to be raised here, exactly as ScheduleJobTrigger does for scheduled runs.
        jobNotificationTrigger.notifyStatusChanged(savedJob);
        scheduleJobService.createJobContext(savedJob);

        log.info("Job {} triggered job {} on workspace {} (depth {})",
                completedJob.getId(), savedJob.getId(), destination.getName(), depth);
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
