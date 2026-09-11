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
 * Commits one triggered job, and nothing else.
 *
 * <p>A separate bean on purpose. The transaction boundary has to be per dependent, and it has
 * to close before the caller schedules the job with Quartz - two requirements that a
 * {@code @Transactional} method called from inside {@link RunTriggerDispatchService} would not
 * meet, because a self-invocation never passes through the Spring proxy.
 *
 * <p>Why per dependent: a failed insert marks its transaction rollback-only, and catching the
 * exception does not clear that mark. Sharing one transaction across the fan-out would let a
 * single bad dependent roll back every job already created beside it, right through the
 * try/catch that exists to prevent exactly that.
 *
 * <p>Why it must commit before scheduling: {@code ScheduleJobService.createJobContext} fires
 * the Quartz trigger immediately, and the worker picks the job up on another connection. While
 * this transaction is open that row is invisible, and ScheduleJob treats a job it cannot find
 * as deleted and drops the trigger for good. Elide's JobManageHook avoids the same trap by
 * binding to POSTCOMMIT.
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

        return jobRepository.save(job);
    }
}
