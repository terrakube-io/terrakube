package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * Builds triggered jobs with the same options and provenance in every dispatch path. The legacy
 * writer commits a standalone job; cascade dispatch instead joins the coordinator's transaction
 * so the job, attempt and workspace-run state either all commit or all roll back. Callers schedule
 * with Quartz only after commit, when its worker can see the job on another connection.
 */
@Service
@AllArgsConstructor
class RunTriggerJobWriter {

    private final JobRepository jobRepository;

    /**
     * The destination and its organization arrive detached, from the caller's own read. That is
     * safe here: both are plain ManyToOne associations with no cascade, so only their foreign
     * keys are written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job persist(Workspace destination, String templateReference, Job completedJob, int depth) {
        return save(destination, templateReference, completedJob, depth, null);
    }

    /** Job and cascade attempt must commit together; the coordinator owns this transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Job persistInCascade(Workspace destination, String templateReference, Job parent, int depth, String tcl) {
        return save(destination, templateReference, parent, depth, tcl);
    }

    private Job save(Workspace destination, String templateReference, Job completedJob, int depth, String tcl) {
        Date now = new Date(System.currentTimeMillis());

        Job job = new Job();
        job.setWorkspace(destination);
        job.setOrganization(destination.getOrganization());
        job.setTemplateReference(templateReference);
        job.setTcl(tcl);
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

        return jobRepository.save(job);
    }
}
