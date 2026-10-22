package io.terrakube.api.plugin.scheduler.inactive;

import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;

import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

// One pass at a time, also across API replicas (the Quartz store is shared). The per-job lock below covers
// the other jobs that touch the same run.
@DisallowConcurrentExecution
@Service
@Slf4j
@AllArgsConstructor
public class InactiveJobs implements org.quartz.Job {

    private static final String EXECUTION_LOCK_PREFIX = "job-execution-lock:";
    private static final Duration EXECUTION_LOCK_TTL = Duration.ofSeconds(90);

    private final GitLabWebhookService gitLabWebhookService;
    private final JobRepository jobRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final GitHubWebhookService gitHubWebhookService;
    private final AzDevOpsWebhookService azDevOpsWebhookService;
    private final StepRepository stepRepository;
    private final InactiveJobsProperties properties;
    private final JobStatusChangeService jobStatusChangeService;

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        for (Job job : jobRepository.findAllByStatusInOrderByIdAsc(Arrays.asList(
                JobStatus.pending,
                JobStatus.running,
                JobStatus.queue,
                JobStatus.waitingApproval))) {
            if (job.getCreatedDate() == null) {
                log.warn("Skipping inactive job {} because it has no created date", job.getId());
                continue;
            }

            Optional<InactiveJobsProperties.Expiry> expiry = properties.expiryOf(job, new Date(System.currentTimeMillis()), jobRepository);
            if (expiry.isEmpty()) {
                continue;
            }

            // The same per-job lock ScheduleJob and the reconciliation sweep take: a run that is being
            // dispatched or reconciled right now is not torn down under them.
            if (!acquireExecutionLock(job.getId())) {
                log.info("Job {} is being processed by another worker, it is checked again on the next pass", job.getId());
                continue;
            }
            try {
                log.warn("Closing Job {} as {}: {}", job.getId(), expiry.get().finalStatus(), expiry.get().reason());
                closeExpiredJob(job, expiry.get(), jobExecutionContext);
            } catch (Exception e) {
                log.error("Failed to close inactive job {}", job.getId(), e);
            } finally {
                releaseExecutionLock(job.getId());
            }
        }
    }

    private void closeExpiredJob(Job job, InactiveJobsProperties.Expiry expiry, JobExecutionContext jobExecutionContext) throws Exception {
        expiry.applyTo(job);
        jobRepository.save(job);
        redisTemplate.delete(String.valueOf(job.getId()));
        // Steps first: the failure summary in the notification is read from the step that failed.
        failPendingSteps(job, expiry.finalStatus());
        jobStatusChangeService.afterStatusChanged(job);

        if (job.getWorkspace() == null) {
            log.warn("Job {} belongs to a deleted or filtered workspace, skipping VCS status update", job.getId());
            jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
            return;
        }

        if (isManualOrScheduledJob(job)) {
            log.info("No VCS status update required for job {}", job.getId());
            jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
            return;
        }

        Vcs workspaceVcs = job.getWorkspace().getVcs();
        if (workspaceVcs == null) {
            log.info("Workspace {} has no VCS configured, skipping VCS status update for job {}",
                    job.getWorkspace().getId(), job.getId());
            jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
            return;
        }

        switch (workspaceVcs.getVcsType()) {
            case GITHUB:
                log.info("Updating VCS information for GITHUB on job {}", job.getId());
                gitHubWebhookService.sendCommitStatus(job, expiry.finalStatus(), null);
                break;
            case GITLAB:
                log.info("Updating VCS information for GITLAB on job {}", job.getId());
                gitLabWebhookService.sendCommitStatus(job, expiry.finalStatus(), null);
                break;
            case AZURE_DEVOPS:
            case AZURE_SP_MI:
                log.info("Updating VCS information for AZURE_DEVOPS on job {}", job.getId());
                azDevOpsWebhookService.sendCommitStatus(job, expiry.finalStatus(), null);
                break;
            default:
                break;
        }

        jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
    }

    // Fails closed like ScheduleJob.acquireExecutionLock: an unreachable Redis means "skip this job this pass".
    private boolean acquireExecutionLock(int jobId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(EXECUTION_LOCK_PREFIX + jobId, "1", EXECUTION_LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("Could not reach Redis for the execution lock on job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private void releaseExecutionLock(int jobId) {
        try {
            redisTemplate.delete(EXECUTION_LOCK_PREFIX + jobId);
        } catch (DataAccessException e) {
            log.warn("Could not release the execution lock on job {} (its TTL clears it): {}", jobId, e.getMessage());
        }
    }

    private void failPendingSteps(Job job, JobStatus finalStatus) {
        log.warn("Closing pending steps as {}", finalStatus);
        for (Step step : stepRepository.findByJobId(job.getId())) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(finalStatus);
                stepRepository.save(step);
            }
        }
    }

    private boolean isManualOrScheduledJob(Job job) {
        return JobVia.CLI.getValue().equals(job.getVia())
                || JobVia.UI.getValue().equals(job.getVia())
                || JobVia.SCHEDULE.getValue().equals(job.getVia());
    }
}
