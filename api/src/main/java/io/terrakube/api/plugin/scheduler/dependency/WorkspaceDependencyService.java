package io.terrakube.api.plugin.scheduler.dependency;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceDependencyRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.dependency.WorkspaceDependency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Triggers runs on workspaces that consume output from a workspace that just applied.
 *
 * The typical case is a workspace reading another one's state through
 * terraform_remote_state - a VPN that needs the VPC's outputs, for example. Today that
 * consumer only re-plans when someone remembers to run it, so the graph drifts silently
 * until a plan surprises whoever is on call.
 *
 * Scope is deliberately narrow: this only *triggers* dependent runs, it does not block or
 * order them. Ordering (refusing to apply a consumer while its producer is running or
 * stale) is a larger change and is intentionally left out of this iteration - see the pull
 * request description.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceDependencyService {

    private static final String TRIGGERED_VIA = "Dependency";

    private final WorkspaceDependencyRepository workspaceDependencyRepository;
    private final JobRepository jobRepository;
    private final ScheduleJobService scheduleJobService;

    /**
     * Guards against a dependency cycle turning into an endless chain of runs. A run
     * triggered by a dependency does not itself trigger further runs beyond this depth.
     */
    @Value("${io.terrakube.dependency.maxCascadeDepth:5}")
    private int maxCascadeDepth;

    @Value("${io.terrakube.dependency.enabled:true}")
    private boolean enabled;

    /**
     * Upper bound on how many consumers a single apply may trigger. HCP Terraform caps run
     * triggers at 20 source workspaces; this is the same protection seen from the producer
     * side, so one apply in a badly modelled graph cannot queue an unbounded number of runs.
     */
    @Value("${io.terrakube.dependency.maxDependentsPerApply:20}")
    private int maxDependentsPerApply;

    /**
     * Called when a job reaches a successful terminal state. Creates a job on every
     * workspace that declares a dependency on this one.
     */
    public void triggerDependents(Job completedJob) {
        if (!enabled || completedJob == null || completedJob.getWorkspace() == null) {
            return;
        }

        Workspace producer = completedJob.getWorkspace();
        List<WorkspaceDependency> dependents =
                workspaceDependencyRepository.findByDependsOnId(producer.getId());

        if (dependents.isEmpty()) {
            return;
        }

        int depth = cascadeDepth(completedJob);
        if (depth >= maxCascadeDepth) {
            log.warn("Not triggering dependents of workspace {}: cascade depth {} reached the limit of {}. " +
                            "This usually means the dependency graph has a cycle.",
                    producer.getName(), depth, maxCascadeDepth);
            return;
        }

        Set<UUID> alreadyTriggered = new HashSet<>();
        for (WorkspaceDependency dependency : dependents) {
            Workspace consumer = dependency.getWorkspace();
            if (consumer == null || consumer.isDeleted()) {
                continue;
            }
            // the same consumer may declare more than one dependency on the producer's
            // graph; one run per apply is enough
            if (!alreadyTriggered.add(consumer.getId())) {
                continue;
            }
            if (alreadyTriggered.size() > maxDependentsPerApply) {
                log.warn("Workspace {} has more than {} dependents; not triggering the rest. " +
                                "Raise io.terrakube.dependency.maxDependentsPerApply if this is intended.",
                        producer.getName(), maxDependentsPerApply);
                break;
            }
            createDependentJob(consumer, dependency, producer, depth + 1, completedJob.getId());
        }
    }

    private void createDependentJob(Workspace consumer, WorkspaceDependency dependency,
                                    Workspace producer, int depth, int triggeredByJobId) {
        String template = dependency.getTemplateReference() != null
                ? dependency.getTemplateReference()
                : consumer.getDefaultTemplate();

        if (template == null) {
            log.warn("Workspace {} depends on {} but has no template to run: set a default template " +
                            "on the workspace or a templateReference on the dependency.",
                    consumer.getName(), producer.getName());
            return;
        }

        Date now = new Date(System.currentTimeMillis());
        Job job = new Job();
        job.setTemplateReference(template);
        job.setRefresh(true);
        job.setPlanChanges(true);
        job.setRefreshOnly(false);
        job.setOrganization(consumer.getOrganization());
        job.setWorkspace(consumer);
        job.setCreatedBy(TRIGGERED_VIA);
        job.setUpdatedBy(TRIGGERED_VIA);
        job.setCreatedDate(new Timestamp(now.getTime()));
        job.setUpdatedDate(new Timestamp(now.getTime()));
        job.setVia(TRIGGERED_VIA);
        job.setStatus(JobStatus.pending);
        job.setDependencyDepth(depth);
        job.setTriggeredByJobId(triggeredByJobId);

        Job savedJob = jobRepository.save(job);
        try {
            scheduleJobService.createJobContext(savedJob);
        } catch (Exception e) {
            // A dependent run that fails to schedule must not take down the producer's own
            // completion handling: the producer applied successfully and that outcome stands.
            log.error("Workspace {} depends on {} but the triggered job {} could not be scheduled",
                    consumer.getName(), producer.getName(), savedJob.getId(), e);
            return;
        }

        log.info("Workspace {} depends on {}: triggered job {} (cascade depth {})",
                consumer.getName(), producer.getName(), savedJob.getId(), depth);
    }

    private int cascadeDepth(Job job) {
        return job.getDependencyDepth() == null ? 0 : job.getDependencyDepth();
    }
}
