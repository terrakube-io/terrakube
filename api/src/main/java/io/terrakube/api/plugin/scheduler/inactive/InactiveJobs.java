package io.terrakube.api.plugin.scheduler.inactive;

import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.scheduler.JobStatusChangeService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Optional;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

@Service
@Slf4j
@AllArgsConstructor
public class InactiveJobs implements org.quartz.Job {

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

            Optional<InactiveJobsProperties.Expiry> expiry = properties.expiryOf(job, new Date(System.currentTimeMillis()));
            if (expiry.isEmpty()) {
                continue;
            }

            log.warn("Closing Job {} as {}: {}", job.getId(), expiry.get().finalStatus(), expiry.get().reason());
            try {
                closeExpiredJob(job, expiry.get(), jobExecutionContext);
            } catch (Exception e) {
                log.error("Failed to close inactive job {}", job.getId(), e);
            }
        }
    }

    private void closeExpiredJob(Job job, InactiveJobsProperties.Expiry expiry, JobExecutionContext jobExecutionContext) throws Exception {
        expiry.applyTo(job);
        jobRepository.save(job);
        redisTemplate.delete(String.valueOf(job.getId()));
        jobStatusChangeService.afterStatusChanged(job);

        failPendingSteps(job, expiry.finalStatus());

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
                gitHubWebhookService.sendCommitStatus(job, JobStatus.unknown, null);
                break;
            case GITLAB:
                log.info("Updating VCS information for GITLAB on job {}", job.getId());
                gitLabWebhookService.sendCommitStatus(job, JobStatus.unknown, null);
                break;
            case AZURE_DEVOPS:
            case AZURE_SP_MI:
                log.info("Updating VCS information for AZURE_DEVOPS on job {}", job.getId());
                azDevOpsWebhookService.sendCommitStatus(job, JobStatus.unknown, null);
                break;
            default:
                break;
        }

        jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
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
