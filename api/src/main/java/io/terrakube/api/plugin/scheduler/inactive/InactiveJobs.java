package io.terrakube.api.plugin.scheduler.inactive;

import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
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
    JobRepository jobRepository;
    RedisTemplate redisTemplate;
    GitHubWebhookService gitHubWebhookService;
    StepRepository stepRepository;

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        for (Job job : jobRepository.findAllByStatusInOrderByIdAsc(Arrays.asList(
                JobStatus.pending,
                JobStatus.running,
                JobStatus.queue,
                JobStatus.waitingApproval))) {
            Date currentTime = new Date(System.currentTimeMillis());
            Date jobExpirationDate = DateUtils.addHours(job.getCreatedDate(), 6);
            log.info("Inactive Job {} should be completed before {}, current time {}", job.getId(), jobExpirationDate, currentTime);
            if (currentTime.after(jobExpirationDate)) {
                try {
                    log.error("Job has been running for more than 6 hours, cancelling running job {}", job.getId());
                    job.setStatus(JobStatus.failed);
                    jobRepository.save(job);
                    redisTemplate.delete(String.valueOf(job.getId()));
                    log.warn("Cancelling pending steps");
                    for (Step step : stepRepository.findByJobId(job.getId())) {
                        if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                            step.setStatus(JobStatus.failed);
                            stepRepository.save(step);
                        }
                    }
                    if (job.getVia().equals(JobVia.CLI.name()) || job.getVia().equals(JobVia.UI.name()) || job.getVia().equals(JobVia.Schedule.name())) {
                        log.info("No information to update for job", job.getId());
                        return;
                    } else {

                        switch (job.getWorkspace().getVcs().getVcsType()) {
                            case GITHUB:
                                log.info("Updating VCS information for GITHUB", job.getId());
                                gitHubWebhookService.sendCommitStatus(job, JobStatus.unknown);
                                break;
                            case GITLAB:
                                log.info("Updating VCS information for GITLAB", job.getId());
                                gitLabWebhookService.sendCommitStatus(job, JobStatus.unknown);
                                break;
                            default:
                                break;
                        }
                    }
                    jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
                log.warn("Closing Job");
            }
        }
    }

}
