package io.terrakube.api.plugin.scheduler;

import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.data.redis.core.RedisTemplate;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.scheduler.job.tcl.model.ScheduleTemplate;
import io.terrakube.api.plugin.softdelete.SoftDeleteService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.schedule.Schedule;

import java.text.ParseException;
import java.util.*;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

@AllArgsConstructor
@Component
@Getter
@Setter
@Slf4j
public class ScheduleJob implements org.quartz.Job {
    private final ScheduleRepository scheduleRepository;
    private final TemplateRepository templateRepository;

    public static final String JOB_ID = "jobId";
    private final EphemeralExecutorService ephemeralExecutorService;
    private final GitLabWebhookService gitLabWebhookService;

    JobRepository jobRepository;

    StepRepository stepRepository;
    TclService tclService;
    ExecutorService executorService;

    WorkspaceRepository workspaceRepository;

    SoftDeleteService softDeleteService;

    ScheduleJobService scheduleJobService;

    RedisTemplate redisTemplate;

    GitHubWebhookService gitHubWebhookService;

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        int jobId = jobExecutionContext.getJobDetail().getJobDataMap().getInt(JOB_ID);
        Job job = jobRepository.getReferenceById(jobId);

        Date jobExpiration = DateUtils.addHours(job.getCreatedDate(), 6);
        Date currentTime = new Date(System.currentTimeMillis());
        log.info("Job {} should be completed before {}, current time {}", job.getId(), jobExpiration, currentTime);
        if (currentTime.after(jobExpiration)) {
            log.error("Job has been running for more than 6 hours, cancelling running job");
            try {
                job.setStatus(JobStatus.failed);
                jobRepository.save(job);
                redisTemplate.delete(String.valueOf(job.getId()));
                log.warn("Deleting Job Context {} from Quartz", PREFIX_JOB_CONTEXT + job.getId());
                updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                updateJobStatusOnVcs(job, JobStatus.unknown);
                removeJobContext(job, jobExecutionContext);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            log.warn("Closing Job");
            return;
        }

        if (job.getWorkspace() == null) {
            log.warn("Workspace does not exist anymore, deleting job context for {}", jobId);
            removeJobContext(job, jobExecutionContext);
            return;
        }

        if (job.getWorkspace().isLocked()) {
            log.warn("Job {}, Workspace is locked. It must be unlocked before Terrakube can execute it.", jobId);
            return;
        }

        log.info("Checking Job {} Status {}", job.getId(), job.getStatus());
        log.info("Checking previous jobs....");
        Optional<List<Job>> previousJobs = jobRepository.findByWorkspaceAndStatusNotInAndIdLessThan(job.getWorkspace(),
                Arrays.asList(JobStatus.failed, JobStatus.completed, JobStatus.rejected, JobStatus.cancelled, JobStatus.noChanges),
                job.getId()
        );
        if (previousJobs.isPresent() && !previousJobs.get().isEmpty()) {
            log.warn("Job {} is waiting for previous jobs to be completed...", jobId);
        } else {

            switch (job.getStatus()) {
                case pending:
                    log.info("Pending with plan changes {}", job.isPlanChanges());
                    if (job.isPlanChanges()) {
                        redisTemplate.delete(String.valueOf(job.getId()));
                        executePendingJob(job, jobExecutionContext);
                        removeJobContext(job, jobExecutionContext);
                    } else {
                        log.warn("Job {} completed with no changes...", jobId);
                        completeJob(job);
                        redisTemplate.delete(String.valueOf(job.getId()));
                        updateJobStepsWithStatus(job.getId(), JobStatus.notExecuted);
                        updateJobStatusOnVcs(job, JobStatus.completed);
                    }
                    break;
                case approved:
                    executeApprovedJobs(job);
                    break;
                case running:
                    log.info("Job {} running", job.getId());
                    break;
                case completed:
                    redisTemplate.delete(String.valueOf(job.getId()));
                    removeJobContext(job, jobExecutionContext);
                    ephemeralExecutorService.deleteEphemeralJob(job);
                    updateJobStatusOnVcs(job, JobStatus.completed);
                    break;
                case cancelled:
                case failed:
                case rejected:
                    redisTemplate.delete(String.valueOf(job.getId()));
                    log.info("Deleting Failed/Cancelled/Rejected Job Context {} from Quartz", PREFIX_JOB_CONTEXT + job.getId());
                    updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                    updateJobStatusOnVcs(job, JobStatus.failed);
                    removeJobContext(job, jobExecutionContext);
                    ephemeralExecutorService.deleteEphemeralJob(job);
                    break;
                default:
                    log.info("Job {} Status {}", job.getId(), job.getStatus());
                    break;
            }
        }
    }

    private void executePendingJob(Job job, JobExecutionContext jobExecutionContext) {
        job = tclService.initJobConfiguration(job);

        Optional<Flow> flow = Optional.ofNullable(tclService.getNextFlow(job));
        if (flow.isPresent()) {
            log.info("Execute command: {} \n {}", flow.get().getType(), flow.get().getCommands());
            String stepId = tclService.getCurrentStepId(job);
            FlowType tempFlowType = FlowType.valueOf(flow.get().getType());
            switch (tempFlowType) {
                case terraformPlanDestroy:
                case terraformPlan:
                case terraformApply:
                case terraformDestroy:
                case customScripts:
                    if (executorService.execute(job, stepId, flow.get()) != null)
                        log.info("Executing Job {} Step Id {}", job.getId(), stepId);
                    else {
                        log.error("Error when sending context to executor marking job {} as failed, step count {}", job.getId(), job.getStep().size());
                        job.setStatus(JobStatus.failed);
                        jobRepository.save(job);
                        Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
                        step.setName("Error sending to executor, check logs");
                        stepRepository.save(step);
                    }
                    break;
                case approval:
                    if (!job.isAutoApply()) {
                        job.setStatus(JobStatus.waitingApproval);
                        job.setApprovalTeam(flow.get().getTeam());
                        jobRepository.save(job);
                        log.info("Waiting Approval for Job {} Step Id {}", job.getId(), stepId);
                    } else {
                        log.info("Auto Approving is enabled for Job {} Step Id {}", job.getId(), stepId);
                        executeApprovedJobs(job);
                    }
                    break;
                case disableWorkspace:
                    log.warn("Disable workspace {} updating status to COMPLETED", job.getId());
                    job.setStatus(JobStatus.completed);
                    jobRepository.save(job);
                    log.warn("Disable workspace scheduler for {} {}", job.getWorkspace().getId(), job.getWorkspace().getName());
                    softDeleteService.disableWorkspaceSchedules(job.getWorkspace());
                    log.warn("Remove current job context");
                    removeJobContext(job, jobExecutionContext);
                    log.warn("Update workspace deleted to true");
                    Workspace workspace = job.getWorkspace();
                    workspace.setDeleted(true);
                    workspace.setName("DELETED_" + UUID.randomUUID());
                    workspaceRepository.save(workspace);
                    break;
                case scheduleTemplates:
                    log.info("Creating new schedules for this workspace");
                    if (setupScheduler(job, flow.get())) {
                        log.info("Schedule completed successfully");

                        Step step = stepRepository.getReferenceById(UUID.fromString(stepId));
                        step.setStatus(JobStatus.completed);
                        log.info("Updating Step {} to completed", stepId);
                        stepRepository.save(step);

                        log.info("Updating Job {} to pending to continue execution", stepId);
                        job.setStatus(JobStatus.pending);
                        jobRepository.save(job);
                    } else {
                        job.setStatus(JobStatus.failed);
                        jobRepository.save(job);
                    }
                    break;
                case yamlError:
                    log.error("Terrakube Template error, please verify the template definition");
                    job.setStatus(JobStatus.failed);
                    jobRepository.save(job);
                    updateJobStepsWithStatus(job.getId(), JobStatus.failed);
                    updateJobStatusOnVcs(job, JobStatus.unknown);
                    break;
                default:
                    log.error("FlowType not supported");
                    break;
            }
        } else {
            completeJob(job);
            removeJobContext(job, jobExecutionContext);
        }
    }

    private boolean setupScheduler(Job job, Flow flow) {
        boolean success = true;
        for (ScheduleTemplate scheduleTemplate : flow.getTemplates()) {
            Template template = templateRepository.getByOrganizationNameAndName(job.getOrganization().getName(), scheduleTemplate.getName());

            if (template != null) {
                Schedule schedule = new Schedule();
                schedule.setWorkspace(job.getWorkspace());
//                schedule.setId(UUID.randomUUID());
                schedule.setCron(scheduleTemplate.getSchedule());
                schedule.setEnabled(true);
                schedule.setCreatedBy(job.getCreatedBy());
                schedule.setCreatedDate(job.getCreatedDate());
                schedule.setTemplateReference(template.getId().toString());
                schedule.setDescription("Schedule from Job " + job.getId());

                schedule = scheduleRepository.save(schedule);

                try {
                    scheduleJobService.createJobTrigger(schedule.getCron(), schedule.getId().toString());
                } catch (ParseException | SchedulerException e) {
                    log.error(e.getMessage());
                    success = false;
                }
            } else {
                log.error("Unable to find template with name {} in organization {}", scheduleTemplate.getName(), job.getOrganization().getName());
                success = false;
                break;
            }

        }

        return success;
    }

    private void completeJob(Job job) {
        job.setStatus(JobStatus.completed);
        jobRepository.save(job);
        ephemeralExecutorService.deleteEphemeralJob(job);
        updateJobStatusOnVcs(job, JobStatus.completed);
        log.info("Update Job {} to completed", job.getId());
    }

    private void removeJobContext(Job job, JobExecutionContext jobExecutionContext) {
        try {
            Boolean triggerByStatusChange = jobExecutionContext.getJobDetail().getJobDataMap().getBooleanFromString("isTriggerFromStatusChange");
            if (!triggerByStatusChange.booleanValue()) {
                log.info("Deleting Schedule Job Context {}, InstanceId {}", PREFIX_JOB_CONTEXT + job.getId(), jobExecutionContext.getFireInstanceId());
                jobExecutionContext.getScheduler().deleteJob(new JobKey(PREFIX_JOB_CONTEXT + job.getId()));
            } else {
                String jobIdentity = jobExecutionContext.getJobDetail().getJobDataMap().getString("identity");
                jobExecutionContext.getScheduler().deleteJob(new JobKey(jobIdentity));
            }
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        }
    }

    private void executeApprovedJobs(Job job) {
        job = tclService.initJobConfiguration(job);
        Optional<Flow> flow = Optional.ofNullable(tclService.getNextFlow(job));
        if (flow.isPresent()) {
            log.info("Execute command: {} \n {}", flow.get().getType(), flow.get().getCommands());
            String stepId = tclService.getCurrentStepId(job);
            job.setApprovalTeam("");
            jobRepository.save(job);
            if (executorService.execute(job, stepId, flow.get()) != null)
                log.info("Executing Job {} Step Id {}", job.getId(), stepId);
        }
    }

    private void updateJobStepsWithStatus(int jobId, JobStatus jobStatus) {
        log.warn("Cancelling pending steps");
        for (Step step : stepRepository.findByJobId(jobId)) {
            if (step.getStatus().equals(JobStatus.pending) || step.getStatus().equals(JobStatus.running)) {
                step.setStatus(jobStatus);
                stepRepository.save(step);
            }
        }
    }

    private void updateJobStatusOnVcs(Job job, JobStatus jobStatus) {
        if (job.getVia().equals(JobVia.UI.name()) || job.getVia().equals(JobVia.CLI.name()) || job.getVia().equals(JobVia.Schedule.name())) {
            return;
        }

        switch (job.getWorkspace().getVcs().getVcsType()) {
            case GITHUB:
                gitHubWebhookService.sendCommitStatus(job, jobStatus);
                break;
            case GITLAB:
                gitLabWebhookService.sendCommitStatus(job, jobStatus);
                break;
            default:
                break;
        }
    }
}
