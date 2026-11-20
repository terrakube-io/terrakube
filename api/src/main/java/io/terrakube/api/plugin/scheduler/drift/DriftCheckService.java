package io.terrakube.api.plugin.scheduler.drift;

import java.util.Date;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drift is when what is deployed no longer matches the code. Terrakube finds it by running a plan on a schedule, so
 * a scheduled run of a plan-only template is a drift check, and when its plan has changes the workspace is drifted.
 * A run of any other kind, including a scheduled apply, says nothing about drift and is left alone.
 *
 * <p>Called after the plan counts of a run were recorded, so a check that failed before it had a plan never reaches
 * here and leaves the workspace as it was: an unknown result must not turn a drifted workspace clean.
 */
@Slf4j
@Service
@AllArgsConstructor
public class DriftCheckService {

    public static final String DRIFTED = "drifted";
    public static final String CLEAN = "clean";

    private final JobRepository jobRepository;
    private final WorkspaceRepository workspaceRepository;
    private final TclService tclService;

    @Transactional
    public void record(int jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getPlanAdd() == null || job.getPlanChange() == null || job.getPlanDestroy() == null) {
            return;
        }
        if (!JobVia.SCHEDULE.getValue().equals(job.getVia()) || !tclService.isTemplatePlanOnly(job.getTemplateReference())) {
            return;
        }
        if (job.isRefreshOnly()) {
            // A refresh-only plan lists no resource changes, so it cannot tell drift from none.
            return;
        }
        Workspace workspace = job.getWorkspace();
        if (workspace == null) {
            return;
        }

        boolean drifted = job.getPlanAdd() + job.getPlanChange() + job.getPlanDestroy() > 0;
        Date now = new Date();

        // Drift is first seen when the workspace goes from clean (or never checked) to drifted, and stays that date
        // for as long as it keeps drifting.
        Date firstSeen = null;
        if (drifted) {
            boolean alreadyDrifted = DRIFTED.equals(workspace.getDriftStatus()) && workspace.getDriftFirstSeen() != null;
            firstSeen = alreadyDrifted ? workspace.getDriftFirstSeen()
                    : (job.getCreatedDate() != null ? job.getCreatedDate() : now);
        }
        String status = drifted ? DRIFTED : CLEAN;

        jobRepository.updateDriftResult(job.getId(), drifted ? "drift" : "clean");
        // Only the columns above are written, and the entities loaded here are left untouched: a change made to them
        // would be flushed as an update of the whole row and could write back a status the executor changed meanwhile.
        int recorded = workspaceRepository.updateDrift(workspace.getId(), status, firstSeen, now, job.getId());
        if (recorded == 0) {
            log.info("Drift check of job {} on workspace {} ignored: a newer check is already recorded", job.getId(),
                    workspace.getName());
            return;
        }
        log.info("Drift check of workspace {} (job {}): {}", workspace.getName(), job.getId(), status);
    }
}
