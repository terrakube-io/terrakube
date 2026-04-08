package io.terrakube.api.plugin.scheduler.inactive;

import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
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

import java.util.Arrays;
import java.util.Date;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

@Service
@Slf4j
@AllArgsConstructor
public class InactiveJobs implements org.quartz.Job {

    private final GitLabWebhookService gitLabWebhookService;
    private final JobRepository jobRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final GitHubWebhookService gitHubWebhookService;
    private final StepRepository stepRepository;

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

            Date currentTime = new Date(System.currentTimeMillis());
            Date jobExpirationDate = DateUtils.addHours(job.getCreatedDate(), 6);
            log.info("Inactive Job {} should be completed before {}, current time {}", job.getId(), jobExpirationDate, currentTime);
            if (!currentTime.after(jobExpirationDate)) {
                continue;
            }

            try {
                closeExpiredJob(job, jobExecutionContext);
            } catch (Exception e) {
                log.error("Failed to close inactive job {}", job.getId(), e);
            }

            log.warn("Closing Job {}", job.getId());
        }
    }

    private void closeExpiredJob(Job job, JobExecutionContext jobExecutionContext) throws Exception {
        log.error("Job has been running for more than 6 hours, cancelling running job {}", job.getId());
        jobRepository.updateStatusById(JobStatus.failed.name(), job.getId());
        redisTemplate.delete(String.valueOf(job.getId()));

        failPendingSteps(job);

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
                gitHubWebhookService.sendCommitStatus(job, JobStatus.unknown);
                break;
            case GITLAB:
                log.info("Updating VCS information for GITLAB on job {}", job.getId());
                gitLabWebhookService.sendCommitStatus(job, JobStatus.unknown);
                break;
            default:
                break;
        }

        jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
    }

    private void failPendingSteps(Job job) {
        log.warn("Cancelling pending steps");
        for (Step step : stepRepository.findByJobId(job.getId())) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(JobStatus.failed);
                stepRepository.save(step);
            }
        }
    }

    private boolean isManualOrScheduledJob(Job job) {
        return JobVia.CLI.name().equals(job.getVia())
                || JobVia.UI.name().equals(job.getVia())
                || JobVia.Schedule.name().equals(job.getVia());
    }
}
